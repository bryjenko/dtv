/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv

import com.google.common.truth.Truth.assertThat
import io.github.bryjenko.dtv.data.model.CatalogEpisode
import io.github.bryjenko.dtv.data.model.SeriesContinue
import io.github.bryjenko.dtv.data.model.WatchedEpisode
import org.junit.Test

/** One My List row per series: resume the episode in progress, or offer the next one. */
class SeriesContinueTest {

    @Test
    fun `an in progress episode is resumed and does not skip ahead`() {
        val watched = listOf(episode(sourceId = 1, series = "10", id = 5, season = 1, episode = 2, position = 60_000, updated = 50))
        val catalog = mapOf(key(1, "10") to listOf(catalog(4, 1, 1), catalog(5, 1, 2), catalog(6, 1, 3)))

        val rows = SeriesContinue.build(watched, catalog)

        assertThat(rows).hasSize(1)
        assertThat(rows.single().playEpisodeId).isEqualTo(5L)
        assertThat(rows.single().playingNext).isFalse()
        assertThat(rows.single().resumePositionMillis).isEqualTo(60_000L)
    }

    @Test
    fun `a finished episode offers the next one including across a season`() {
        val watched = listOf(
            episode(sourceId = 1, series = "10", id = 5, season = 1, episode = 2, position = FINISHED, updated = 80),
        )
        val catalog = mapOf(
            key(1, "10") to listOf(catalog(4, 1, 1), catalog(5, 1, 2), catalog(8, 2, 1, "Pilot of two")),
        )

        val row = SeriesContinue.build(watched, catalog).single()

        assertThat(row.playingNext).isTrue()
        assertThat(row.playEpisodeId).isEqualTo(8L)
        assertThat(row.season).isEqualTo(2)
        assertThat(row.episodeNumber).isEqualTo(1)
        assertThat(row.resumePositionMillis).isEqualTo(0L)
        assertThat(row.watchedMediaKey).isEqualTo("ep:5")
    }

    @Test
    fun `a finished series with nothing after it drops off the list`() {
        val watched = listOf(
            episode(sourceId = 1, series = "10", id = 5, season = 1, episode = 2, position = FINISHED, updated = 80),
        )
        val catalog = mapOf(key(1, "10") to listOf(catalog(4, 1, 1), catalog(5, 1, 2)))

        assertThat(SeriesContinue.build(watched, catalog)).isEmpty()
    }

    @Test
    fun `the most recently watched episode represents the series`() {
        val watched = listOf(
            episode(sourceId = 1, series = "10", id = 4, season = 1, episode = 1, position = 90_000, updated = 10),
            episode(sourceId = 1, series = "10", id = 5, season = 1, episode = 2, position = 30_000, updated = 90),
        )

        val row = SeriesContinue.build(watched, emptyMap()).single()

        assertThat(row.playEpisodeId).isEqualTo(5L)
        assertThat(row.resumePositionMillis).isEqualTo(30_000L)
    }

    @Test
    fun `most recently watched series comes first`() {
        val watched = listOf(
            episode(sourceId = 1, series = "old", id = 1, season = 1, episode = 1, position = 60_000, updated = 10),
            episode(sourceId = 1, series = "new", id = 2, season = 1, episode = 1, position = 60_000, updated = 90),
        )

        val rows = SeriesContinue.build(watched, emptyMap())

        assertThat(rows.map { it.providerSeriesId }).containsExactly("new", "old").inOrder()
    }

    @Test
    fun `the same show on two playlists stays two rows`() {
        val watched = listOf(
            episode(sourceId = 1, series = "10", id = 5, season = 1, episode = 1, position = 60_000, updated = 40),
            episode(sourceId = 2, series = "10", id = 9, season = 1, episode = 3, position = 60_000, updated = 70),
        )

        val rows = SeriesContinue.build(watched, emptyMap())

        assertThat(rows.map { it.sourceId }).containsExactly(2L, 1L)
        assertThat(rows.map { it.seriesKey }).containsExactly("2:10", "1:10")
        assertThat(rows.map { it.playEpisodeId }).containsExactly(9L, 5L)
    }

    @Test
    fun `a blip at the start does not create a row`() {
        val watched = listOf(
            episode(sourceId = 1, series = "10", id = 5, season = 1, episode = 1, position = 1_000, updated = 10),
        )

        assertThat(SeriesContinue.build(watched, emptyMap())).isEmpty()
    }

    private fun key(sourceId: Long, series: String) = SeriesContinue.seriesKey(sourceId, series)

    private fun episode(
        sourceId: Long,
        series: String,
        id: Long,
        season: Int,
        episode: Int,
        position: Long,
        updated: Long,
    ) = WatchedEpisode(
        seriesKey = key(sourceId, series),
        sourceId = sourceId,
        providerSeriesId = series,
        episodeId = id,
        season = season,
        episodeNumber = episode,
        title = "Episode $episode",
        stillUrl = null,
        streamUrl = "http://play/$sourceId/$id",
        positionMillis = position,
        durationMillis = FORTY_MINUTES,
        updatedAtMillis = updated,
    )

    private fun catalog(id: Long, season: Int, episode: Int, title: String = "Episode $episode") = CatalogEpisode(
        episodeId = id,
        season = season,
        episodeNumber = episode,
        title = title,
        stillUrl = null,
        streamUrl = "http://play/$id",
    )

    private companion object {
        const val FORTY_MINUTES = 40L * 60L * 1000L
        const val FINISHED = FORTY_MINUTES - 60_000L
    }
}
