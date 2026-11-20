/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv.data.model

/**
 * Which episode OK should resume for a starred series on My List.
 *
 * The star on a show's page writes the existing `series.favourite` flag. That row already
 * belongs to one playlist: [FavouriteSeriesKey.sourceId] plus the provider's own series id.
 * An in-progress episode only counts when it is that same show on that same playlist, so two
 * providers never share a resume point. A finished episode, or one that was only opened for
 * a few seconds, does not count — OK opens the series page instead.
 */
data class FavouriteSeriesKey(
    val localId: Long,
    val sourceId: Long,
    val providerSeriesId: String,
)

/** One episode the active profile has actually started, already joined to its show. */
data class WatchedEpisodeProgress(
    val sourceId: Long,
    val providerSeriesId: String,
    val episodeId: Long,
    val season: Int,
    val episodeNumber: Int,
    val title: String,
    val streamUrl: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long,
)

/** The episode My List should resume for one starred series. */
data class SeriesResume(
    val localSeriesId: Long,
    val mediaKey: String,
    val streamUrl: String,
    val season: Int,
    val episodeNumber: Int,
    val episodeTitle: String,
    val progress: Float,
    val updatedAtMillis: Long,
)

object FavouriteSeriesResume {
    fun seriesKey(sourceId: Long, providerSeriesId: String): String = "$sourceId:$providerSeriesId"

    /**
     * The in-progress episode for each starred series, keyed by the series row id.
     *
     * A series with no entry opens its detail page. When several episodes of the same show
     * are still in progress, the one watched most recently wins. Finished episodes are
     * ignored, so a completed episode never hides an earlier one that is still open.
     * Callers keep [favourites] in their own order; this map does not sort them.
     */
    fun pick(
        favourites: List<FavouriteSeriesKey>,
        episodes: List<WatchedEpisodeProgress>,
    ): Map<Long, SeriesResume> {
        if (favourites.isEmpty() || episodes.isEmpty()) return emptyMap()
        val favouriteByKey = HashMap<String, FavouriteSeriesKey>(favourites.size)
        for (series in favourites) {
            favouriteByKey[seriesKey(series.sourceId, series.providerSeriesId)] = series
        }
        val best = HashMap<Long, WatchedEpisodeProgress>()
        for (episode in episodes) {
            if (!WatchProgress.keepOnContinueWatching(episode.positionMillis, episode.durationMillis)) continue
            val series = favouriteByKey[seriesKey(episode.sourceId, episode.providerSeriesId)] ?: continue
            val current = best[series.localId]
            if (current == null || episode.updatedAtMillis > current.updatedAtMillis) {
                best[series.localId] = episode
            }
        }
        return best.mapValues { (seriesId, episode) ->
            SeriesResume(
                localSeriesId = seriesId,
                mediaKey = "ep:${episode.episodeId}",
                streamUrl = episode.streamUrl,
                season = episode.season,
                episodeNumber = episode.episodeNumber,
                episodeTitle = episode.title,
                progress = if (episode.durationMillis > 0L) {
                    (episode.positionMillis.toFloat() / episode.durationMillis.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                },
                updatedAtMillis = episode.updatedAtMillis,
            )
        }
    }
}
