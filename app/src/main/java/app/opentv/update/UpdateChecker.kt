/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.update

import app.opentv.data.remote.GitHubRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Checks whether a newer OpenTV release has been published, so a sideloaded install can
 * update itself.
 *
 * Sideloaded apps get no automatic updates — nothing on the device knows to look. Without
 * this, whoever installs v0.1 stays on v0.1 until they happen to hear there is a newer one
 * and go and fetch it by hand. The app store equivalents (Play, Amazon) push updates for
 * you; the Downloader-app install route we ship through does not, so the app has to do it.
 *
 * ## Design
 * - **Quiet automatic checks.** [check] treats network failures and missing releases as no update,
 *   so a background check never nags at launch. [checkDetailed] keeps those states distinct for
 *   the manual status shown in About.
 * - **Compares the tag, not a build number.** GitHub gives us a tag like `v0.2.0`. We parse it
 *   as a dotted version and compare it to our own [currentVersionName]. A release whose tag is
 *   not strictly newer is ignored, so re-tagging or a same-version hotfix will not prompt.
 * - **Skips drafts and pre-releases**, which are not meant for the general audience.
 */
class UpdateChecker(
    private val http: OkHttpClient,
    private val currentVersionName: String,
    private val repoSlug: String = REPO_SLUG,
) {
    sealed interface CheckResult {
        data class Available(val update: Update) : CheckResult
        data object UpToDate : CheckResult
        data object NoRelease : CheckResult
        data object MissingApk : CheckResult
        data object Failed : CheckResult
    }

    data class Update(
        val versionName: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val releaseUrl: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns a newer release, or null if we are current / could not tell. */
    suspend fun check(): Update? = (checkDetailed() as? CheckResult.Available)?.update

    /** A manual check can show a useful status for missing releases and network failures. */
    suspend fun checkDetailed(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repoSlug/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OpenTV")
                .build()

            http.newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext CheckResult.NoRelease
                if (!response.isSuccessful) return@withContext CheckResult.Failed
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext CheckResult.Failed

                val release = json.decodeFromString<GitHubRelease>(body)
                if (release.draft || release.prerelease) return@withContext CheckResult.NoRelease

                val asset = release.apkAsset() ?: return@withContext CheckResult.MissingApk
                if (!isNewer(release.tagName, currentVersionName)) return@withContext CheckResult.UpToDate

                CheckResult.Available(
                    Update(
                        versionName = release.tagName.trimStart('v', 'V'),
                        title = release.name.ifBlank { release.tagName },
                        notes = cleanNotes(release.body),
                        apkUrl = asset.browserDownloadUrl,
                        apkSizeBytes = asset.size,
                        releaseUrl = release.htmlUrl,
                    ),
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            CheckResult.Failed
        }
    }

    companion object {
        /** The single place the project's GitHub location is written down. */
        const val REPO_SLUG = "bryjenko/dtv"

        /**
         * The GitHub release body is written for developers and has GitHub's auto-generated
         * commit list appended. Turn it into a few clean "what's new" lines for the update
         * dialog: drop the build/verify/signing boilerplate and the auto-changelog, and flatten
         * the Markdown that a plain dialog would otherwise show as literal `**`, `[x](y)`, `#`.
         * If nothing user-facing is left, returns empty and the dialog simply omits the notes.
         */
        fun cleanNotes(raw: String): String {
            var text = raw
            // Everything from the first boilerplate / auto-generated marker onward is noise.
            val cutFrom = listOf(
                "Built by GitHub Actions",
                "**Full Changelog**",
                "## What's Changed",
                "<!--",
            )
            for (marker in cutFrom) {
                val i = text.indexOf(marker, ignoreCase = true)
                if (i >= 0) text = text.substring(0, i)
            }
            text = text
                .replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1") // [label](url) -> label
                .replace(Regex("""[*_]{1,3}([^*_\n]+)[*_]{1,3}"""), "$1") // **bold**/_em_ -> plain
                .replace("`", "")                                   // inline-code ticks
                .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")    // ## headers -> text
                .replace(Regex("""(?m)^\s*[-*]\s+"""), "• ")        // "- item" -> "• item"
                .replace(Regex("""(?m)^\s*-{3,}\s*$"""), "")        // --- horizontal rules
            return text.lines().joinToString("\n") { it.trimEnd() }
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
                .take(1200)
        }

        /**
         * True when [tag] names a strictly higher version than [current].
         *
         * Both are reduced to lists of integers (`v0.2.0` → 0,2,0) and compared left to right.
         * A missing or non-numeric component counts as 0, so `0.2` and `0.2.0` are equal and a
         * malformed tag simply never wins.
         */
        fun isNewer(tag: String, current: String): Boolean {
            val a = versionParts(tag)
            val b = versionParts(current)
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val ai = a.getOrElse(i) { 0 }
                val bi = b.getOrElse(i) { 0 }
                if (ai != bi) return ai > bi
            }
            return false
        }

        private fun versionParts(v: String): List<Int> =
            v.trim().trimStart('v', 'V')
                .split('.', '-', '+')
                .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
