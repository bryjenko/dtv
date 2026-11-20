/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv.data.model

/**
 * Rules for "still watching" versus "finished", shared by the player and My List.
 *
 * A title is finished when playback has started and either about 95% of it has been watched, or
 * three minutes or less remain. Movies that hit that point leave Continue Watching. A finished
 * episode stays recorded so the series row can offer the next episode instead.
 */
object WatchProgress {
    /** At or beyond this percentage of the runtime, the title counts as finished. Integer so the edge is exact. */
    const val FINISHED_PERCENT = 95

    /**
     * Also finished when this much (or less) is left, even if the fraction has not been reached.
     * Three minutes is "a few": it catches the end of a long film, where 5% is still several
     * minutes of credits, and the end of a shorter episode, where 5% is only a few seconds.
     */
    const val FINISHED_TAIL_MILLIS = 3L * 60L * 1000L

    /** Ignore the opening seconds so a mis-tap does not land on Continue Watching. */
    const val MIN_SAVED_POSITION_MILLIS = 5_000L

    /**
     * True when [positionMillis] is far enough through [durationMillis] to count as watched.
     * Unknown or not-yet-started playback is never finished.
     */
    fun isFinished(positionMillis: Long, durationMillis: Long): Boolean {
        if (durationMillis <= 0L || positionMillis <= 0L) return false
        if (positionMillis >= durationMillis) return true
        // Integer compare: position / duration >= 95%, without a double edge on the boundary.
        if (positionMillis * 100 >= durationMillis * FINISHED_PERCENT) return true
        val remaining = durationMillis - positionMillis
        return positionMillis > MIN_SAVED_POSITION_MILLIS && remaining <= FINISHED_TAIL_MILLIS
    }

    /** A movie belongs on Continue Watching only after it has been started and before it is finished. */
    fun keepOnContinueWatching(positionMillis: Long, durationMillis: Long): Boolean {
        if (positionMillis <= MIN_SAVED_POSITION_MILLIS) return false
        return !isFinished(positionMillis, durationMillis)
    }

    /**
     * What to do with a progress sample.
     *
     * Movies that are finished are deleted so they leave My List. Finished episodes are kept: the
     * series row uses them to offer the next episode. Recordings and catch-up keep a plain resume
     * point and are never deleted from here.
     */
    fun saveAction(kind: String, positionMillis: Long, durationMillis: Long): SaveAction {
        if (durationMillis <= 0L || positionMillis < 0L) return SaveAction.IGNORE
        return when (kind) {
            "movie" -> when {
                isFinished(positionMillis, durationMillis) -> SaveAction.DELETE
                positionMillis <= MIN_SAVED_POSITION_MILLIS -> SaveAction.IGNORE
                else -> SaveAction.UPSERT
            }
            "ep" -> when {
                isFinished(positionMillis, durationMillis) -> SaveAction.UPSERT
                positionMillis <= MIN_SAVED_POSITION_MILLIS -> SaveAction.IGNORE
                else -> SaveAction.UPSERT
            }
            else -> if (positionMillis > MIN_SAVED_POSITION_MILLIS) SaveAction.UPSERT else SaveAction.IGNORE
        }
    }
}

enum class SaveAction { IGNORE, UPSERT, DELETE }

/** `movie:42` / `ep:7` — the shape every resume row already uses. */
data class ParsedMediaKey(val kind: String, val id: Long)

fun parseMediaKey(mediaKey: String): ParsedMediaKey? {
    val parts = mediaKey.split(':', limit = 2)
    if (parts.size != 2 || parts[0].isEmpty()) return null
    val id = parts[1].toLongOrNull() ?: return null
    return ParsedMediaKey(parts[0], id)
}

/**
 * One watched episode, already joined to its series. [seriesKey] includes the provider so the same
 * show on two playlists never collapses into one row.
 */
data class WatchedEpisode(
    val seriesKey: String,
    val sourceId: Long,
    val providerSeriesId: String,
    val episodeId: Long,
    val season: Int,
    val episodeNumber: Int,
    val title: String,
    val stillUrl: String?,
    val streamUrl: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long,
)

/** An episode from the local catalogue, used only to find "what comes next". */
data class CatalogEpisode(
    val episodeId: Long,
    val season: Int,
    val episodeNumber: Int,
    val title: String,
    val stillUrl: String?,
    val streamUrl: String,
)

/**
 * The one My List row for a series: either the episode still in progress, or the next episode
 * after a finished one.
 */
data class SeriesContinueEntry(
    val seriesKey: String,
    val sourceId: Long,
    val providerSeriesId: String,
    val playEpisodeId: Long,
    val season: Int,
    val episodeNumber: Int,
    val episodeTitle: String,
    val stillUrl: String?,
    val streamUrl: String,
    /** Saved position to resume. Zero when [playingNext] — the next episode starts at the beginning. */
    val resumePositionMillis: Long,
    val durationMillis: Long,
    val progress: Float,
    val playingNext: Boolean,
    val updatedAtMillis: Long,
    /** The position row that put this series on the list. Removing the series deletes this. */
    val watchedMediaKey: String,
)

object SeriesContinue {
    fun seriesKey(sourceId: Long, providerSeriesId: String): String = "$sourceId:$providerSeriesId"

    fun watchedMediaKey(episodeId: Long): String = "ep:$episodeId"

    /**
     * One row per series, most recently watched first.
     *
     * The episode with the latest [WatchedEpisode.updatedAtMillis] wins. If it is still in
     * progress, the row resumes it. If it is finished, the row offers the following episode from
     * [catalogBySeries]; a finished episode with nothing after it drops off the list.
     */
    fun build(
        watched: List<WatchedEpisode>,
        catalogBySeries: Map<String, List<CatalogEpisode>>,
    ): List<SeriesContinueEntry> {
        val latest = watched
            .groupBy { it.seriesKey }
            .mapNotNull { (_, episodes) -> episodes.maxByOrNull { it.updatedAtMillis } }
        return latest.mapNotNull { last -> toEntry(last, catalogBySeries[last.seriesKey].orEmpty()) }
            .sortedByDescending { it.updatedAtMillis }
    }

    private fun toEntry(last: WatchedEpisode, catalog: List<CatalogEpisode>): SeriesContinueEntry? {
        val finished = WatchProgress.isFinished(last.positionMillis, last.durationMillis)
        if (!finished) {
            if (!WatchProgress.keepOnContinueWatching(last.positionMillis, last.durationMillis)) return null
            return entry(
                last = last,
                playId = last.episodeId,
                season = last.season,
                episodeNumber = last.episodeNumber,
                title = last.title,
                stillUrl = last.stillUrl,
                streamUrl = last.streamUrl,
                resumePositionMillis = last.positionMillis,
                durationMillis = last.durationMillis,
                playingNext = false,
            )
        }
        val next = nextAfter(last, catalog) ?: return null
        return entry(
            last = last,
            playId = next.episodeId,
            season = next.season,
            episodeNumber = next.episodeNumber,
            title = next.title,
            stillUrl = next.stillUrl ?: last.stillUrl,
            streamUrl = next.streamUrl,
            resumePositionMillis = 0L,
            durationMillis = 0L,
            playingNext = true,
        )
    }

    private fun entry(
        last: WatchedEpisode,
        playId: Long,
        season: Int,
        episodeNumber: Int,
        title: String,
        stillUrl: String?,
        streamUrl: String,
        resumePositionMillis: Long,
        durationMillis: Long,
        playingNext: Boolean,
    ): SeriesContinueEntry {
        val progress = if (durationMillis > 0L) {
            (resumePositionMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        return SeriesContinueEntry(
            seriesKey = last.seriesKey,
            sourceId = last.sourceId,
            providerSeriesId = last.providerSeriesId,
            playEpisodeId = playId,
            season = season,
            episodeNumber = episodeNumber,
            episodeTitle = title,
            stillUrl = stillUrl,
            streamUrl = streamUrl,
            resumePositionMillis = resumePositionMillis,
            durationMillis = durationMillis,
            progress = progress,
            playingNext = playingNext,
            updatedAtMillis = last.updatedAtMillis,
            watchedMediaKey = watchedMediaKey(last.episodeId),
        )
    }

    /**
     * The episode that follows [last] in season/episode order. When [last] is present in the
     * catalogue, "next" is whatever is stored after it, so a numbering gap still moves forward.
     * Otherwise the first episode strictly after its season and number.
     */
    fun nextAfter(last: WatchedEpisode, catalog: List<CatalogEpisode>): CatalogEpisode? {
        if (catalog.isEmpty()) return null
        val ordered = catalog.sortedWith(compareBy({ it.season }, { it.episodeNumber }, { it.episodeId }))
        val index = ordered.indexOfFirst { it.episodeId == last.episodeId }
        if (index >= 0) return ordered.getOrNull(index + 1)
        return ordered.firstOrNull { episode ->
            episode.season > last.season ||
                (episode.season == last.season && episode.episodeNumber > last.episodeNumber)
        }
    }
}
