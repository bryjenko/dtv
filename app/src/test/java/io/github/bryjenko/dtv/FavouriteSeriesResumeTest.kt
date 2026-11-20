/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv

import com.google.common.truth.Truth.assertThat
import io.github.bryjenko.dtv.data.model.FavouriteSeriesKey
import io.github.bryjenko.dtv.data.model.FavouriteSeriesResume
import io.github.bryjenko.dtv.data.model.WatchedEpisodeProgress
import org.junit.Test

/**
 * A starred show resumes only an episode that is still in progress, and only on the same playlist.
 */
class FavouriteSeriesResumeTest {

    @Test
    fun `a show with nothing in progress has no resume`() {
        val resumes = FavouriteSeriesResume.pick(
            favourites = listOf(series(localId = 7, sourceId = 1, providerId = "10")),
            episodes = emptyList(),
        )

        assertThat(resumes).isEmpty()
    }

    @Test
    fun `an in progress episode is the one to resume`() {
        val resumes = FavouriteSeriesResume.pick(
            favourites = listOf(series(localId = 7, sourceId = 1, providerId = "10")),
            episodes = listOf(episode(sourceId = 1, providerId = "10", id = 5, position = 60_000, updated = 50)),
        )

        val resume = resumes.getValue(7L)
        assertThat(resume.mediaKey).isEqualTo("ep:5")
        assertThat(resume.streamUrl).isEqualTo("http://play/5")
        assertThat(resume.season).isEqualTo(1)
        assertThat(resume.episodeNumber).isEqualTo(2)
        assertThat(resume.progress).isGreaterThan(0f)
        assertThat(resume.progress).isLessThan(1f)
    }

    @Test
    fun `a finished episode is not resumed`() {
        val resumes = FavouriteSeriesResume.pick(
            favourites = listOf(series(localId = 7, sourceId = 1, providerId = "10")),
            episodes = listOf(episode(sourceId = 1, providerId = "10", id = 5, position = FINISHED, updated = 80)),
        )

        assertThat(resumes).isEmpty()
    }

    @Test
    fun `a few seconds in does not count as in progress`() {
        val resumes = FavouriteSeriesResume.pick(
            favourites = listOf(series(localId = 7, sourceId = 1, providerId = "10")),
            episodes = listOf(episode(sourceId = 1, providerId = "10", id = 5, position = 2_000, updated = 80)),
        )

        assertThat(resumes).isEmpty()
    }

    @Test
    fun `the most recently watched in progress episode wins`() {
        val resumes = FavouriteSeriesResume.pick(
            favourites = listOf(series(localId = 7, sourceId = 1, providerId = "10")),
            episodes = listOf(
                episode(sourceId = 1, providerId = "10", id = 4, episodeNumber = 1, position = 90_000, updated = 10),
                episode(sourceId = 1, providerId = "10", id = 5, episodeNumber = 2, position = 30_000, updated = 90),
            ),
        )

        assertThat(resumes.getValue(7L).mediaKey).isEqualTo("ep:5")
        assertThat(resumes.getValue(7L).episodeNumber).isEqualTo(2)
    }

    @Test
    fun `a newer finished episode does not hide an older one still in progress`() {
        val resumes = FavouriteSeriesResume.pick(
            favourites = listOf(series(localId = 7, sourceId = 1, providerId = "10")),
            episodes = listOf(
                episode(sourceId = 1, providerId = "10", id = 4, episodeNumber = 1, position = 90_000, updated = 10),
                episode(sourceId = 1, providerId = "10", id = 5, episodeNumber = 2, position = FINISHED, updated = 90),
            ),
        )

        assertThat(resumes.getValue(7L).mediaKey).isEqualTo("ep:4")
    }

    @Test
    fun `the same show on another playlist does not steal the resume`() {
        val resumes = FavouriteSeriesResume.pick(
            favourites = listOf(
                series(localId = 7, sourceId = 1, providerId = "10"),
                series(localId = 8, sourceId = 2, providerId = "10"),
            ),
            episodes = listOf(episode(sourceId = 2, providerId = "10", id = 5, position = 60_000, updated = 50)),
        )

        assertThat(resumes).doesNotContainKey(7L)
        assertThat(resumes.getValue(8L).mediaKey).isEqualTo("ep:5")
    }

    @Test
    fun `an episode from a different show is ignored`() {
        val resumes = FavouriteSeriesResume.pick(
            favourites = listOf(series(localId = 7, sourceId = 1, providerId = "10")),
            episodes = listOf(episode(sourceId = 1, providerId = "99", id = 5, position = 60_000, updated = 50)),
        )

        assertThat(resumes).isEmpty()
    }

    private fun series(localId: Long, sourceId: Long, providerId: String) = FavouriteSeriesKey(
        localId = localId,
        sourceId = sourceId,
        providerSeriesId = providerId,
    )

    private fun episode(
        sourceId: Long,
        providerId: String,
        id: Long,
        episodeNumber: Int = 2,
        position: Long,
        updated: Long,
    ) = WatchedEpisodeProgress(
        sourceId = sourceId,
        providerSeriesId = providerId,
        episodeId = id,
        season = 1,
        episodeNumber = episodeNumber,
        title = "Chapter",
        streamUrl = "http://play/$id",
        positionMillis = position,
        durationMillis = FORTY_MINUTES,
        updatedAtMillis = updated,
    )

    private companion object {
        const val FORTY_MINUTES = 40L * 60L * 1000L
        const val FINISHED = FORTY_MINUTES - 60_000L
    }
}
