/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv.ui.mylist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bryjenko.dtv.core.ServiceLocator
import io.github.bryjenko.dtv.data.PlaybackProgressStore
import io.github.bryjenko.dtv.data.model.CatalogEpisode
import io.github.bryjenko.dtv.data.model.Channel
import io.github.bryjenko.dtv.data.model.PlaybackPosition
import io.github.bryjenko.dtv.data.model.SeriesContinue
import io.github.bryjenko.dtv.data.model.WatchProgress
import io.github.bryjenko.dtv.data.model.WatchedEpisode
import io.github.bryjenko.dtv.data.model.parseMediaKey
import io.github.bryjenko.dtv.data.parser.displayTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * My List: movies still in progress, one row per series (resume or the next episode), and the
 * channels already starred in the guide. Progress is the existing playback-position table, stamped
 * with the playlist id so two providers stay apart. Favourites are the existing channel flag.
 */
class MyListViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val settings = graph.settings

    /** Series we already asked the provider for, so a progress tick does not refetch. */
    private val attemptedEpisodeFetch = mutableSetOf<String>()

    data class MovieItem(
        val mediaKey: String,
        val sourceId: Long,
        val title: String,
        val posterUrl: String?,
        val streamUrl: String,
        val progress: Float,
    )

    data class ShowItem(
        val seriesKey: String,
        val sourceId: Long,
        val providerSeriesId: String,
        val seriesTitle: String,
        val posterUrl: String?,
        val season: Int,
        val episodeNumber: Int,
        val episodeTitle: String,
        /** The episode OK should play: the one in progress, or the next one after a finished one. */
        val playMediaKey: String,
        val playStreamUrl: String,
        val progress: Float,
        val playingNext: Boolean,
        /** The episode that was actually watched, for "watch again". */
        val watchedMediaKey: String,
        val watchedStreamUrl: String,
        val watchedTitle: String,
        /** Every episode of this show we know about, so Remove clears the series rather than one row. */
        val episodeIds: List<Long>,
    )

    data class Content(
        val movies: List<MovieItem> = emptyList(),
        val shows: List<ShowItem> = emptyList(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val content: StateFlow<Content> = settings.activeProfileId
        .flatMapLatest { profileId -> graph.playbackPositions.observeForProfile(profileId) }
        .mapLatest { positions -> withContext(Dispatchers.IO) { resolve(positions) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Content())

    val channels: StateFlow<List<Channel>> = graph.catalogRepository.observeFavouriteChannels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeMovie(mediaKey: String) {
        viewModelScope.launch {
            graph.playbackPositions.delete(settings.activeProfileId.value, mediaKey)
        }
    }

    fun removeShow(item: ShowItem) {
        viewModelScope.launch {
            val keys = item.episodeIds.map { "ep:$it" } + listOf(item.watchedMediaKey, item.playMediaKey)
            PlaybackProgressStore.deleteKeys(
                graph.playbackPositions,
                settings.activeProfileId.value,
                keys,
            )
        }
    }

    /**
     * Forget the saved position, then play. Awaited so the player does not resume the spot we
     * just cleared. Used by Start over.
     */
    fun playFromStart(mediaKey: String, play: () -> Unit) {
        viewModelScope.launch {
            graph.playbackPositions.delete(settings.activeProfileId.value, mediaKey)
            play()
        }
    }

    fun unfavourite(channel: Channel) {
        viewModelScope.launch {
            graph.catalogRepository.setChannelFavourite(channel.id, false)
        }
    }

    private suspend fun resolve(positions: List<PlaybackPosition>): Content {
        val movies = mutableListOf<MovieItem>()
        val watched = mutableListOf<WatchedEpisode>()
        for (pos in positions) {
            val parsed = parseMediaKey(pos.mediaKey) ?: continue
            when (parsed.kind) {
                "movie" -> {
                    if (!WatchProgress.keepOnContinueWatching(pos.positionMillis, pos.durationMillis)) continue
                    val movie = graph.catalogRepository.movie(parsed.id) ?: continue
                    movies += MovieItem(
                        mediaKey = pos.mediaKey,
                        sourceId = movie.sourceId,
                        title = movie.displayTitle,
                        posterUrl = movie.posterUrl ?: movie.backdropUrl,
                        streamUrl = movie.streamUrl,
                        progress = fraction(pos.positionMillis, pos.durationMillis),
                    )
                }
                "ep" -> {
                    val episode = graph.catalogRepository.episode(parsed.id) ?: continue
                    watched += WatchedEpisode(
                        seriesKey = SeriesContinue.seriesKey(episode.sourceId, episode.seriesId),
                        sourceId = episode.sourceId,
                        providerSeriesId = episode.seriesId,
                        episodeId = episode.id,
                        season = episode.season,
                        episodeNumber = episode.episodeNumber,
                        title = episode.title,
                        stillUrl = episode.stillUrl,
                        streamUrl = episode.streamUrl,
                        positionMillis = pos.positionMillis,
                        durationMillis = pos.durationMillis,
                        updatedAtMillis = pos.updatedAtMillis,
                    )
                }
            }
        }
        return Content(movies = movies, shows = resolveShows(watched))
    }

    private suspend fun resolveShows(watched: List<WatchedEpisode>): List<ShowItem> {
        if (watched.isEmpty()) return emptyList()
        val catalogs = loadCatalogs(watched)
        return SeriesContinue.build(watched, catalogs).mapNotNull { entry ->
            val series = graph.catalogRepository.seriesByProvider(entry.sourceId, entry.providerSeriesId)
            val last = watched.filter { it.seriesKey == entry.seriesKey }.maxByOrNull { it.updatedAtMillis }
                ?: return@mapNotNull null
            val episodeIds = (
                catalogs[entry.seriesKey].orEmpty().map { it.episodeId } +
                    watched.filter { it.seriesKey == entry.seriesKey }.map { it.episodeId }
                ).distinct()
            ShowItem(
                seriesKey = entry.seriesKey,
                sourceId = entry.sourceId,
                providerSeriesId = entry.providerSeriesId,
                seriesTitle = series?.displayTitle?.takeIf { it.isNotBlank() }
                    ?: last.title.ifBlank { "S${entry.season} E${entry.episodeNumber}" },
                posterUrl = series?.posterUrl ?: entry.stillUrl,
                season = entry.season,
                episodeNumber = entry.episodeNumber,
                episodeTitle = entry.episodeTitle,
                playMediaKey = "ep:${entry.playEpisodeId}",
                playStreamUrl = entry.streamUrl,
                progress = entry.progress,
                playingNext = entry.playingNext,
                watchedMediaKey = entry.watchedMediaKey,
                watchedStreamUrl = last.streamUrl,
                watchedTitle = last.title,
                episodeIds = episodeIds,
            )
        }
    }

    /**
     * Local episodes are enough when the one we watched is among them (opening a show downloads
     * the lot). If it isn't — a finished episode whose siblings were never stored — ask the
     * provider once per series so "play next" still has somewhere to go.
     */
    private suspend fun loadCatalogs(watched: List<WatchedEpisode>): Map<String, List<CatalogEpisode>> {
        val latest = watched
            .groupBy { it.seriesKey }
            .mapNotNull { (_, episodes) -> episodes.maxByOrNull { it.updatedAtMillis } }
        val out = mutableMapOf<String, List<CatalogEpisode>>()
        for (last in latest) {
            if (!WatchProgress.isFinished(last.positionMillis, last.durationMillis)) {
                out[last.seriesKey] = emptyList()
                continue
            }
            var rows = graph.catalogRepository.episodesFor(last.sourceId, last.providerSeriesId)
            if (rows.none { it.id == last.episodeId } && attemptedEpisodeFetch.add(last.seriesKey)) {
                val source = graph.sourceRepository.byId(last.sourceId)
                if (source != null) graph.catalogRepository.ensureEpisodes(source, last.providerSeriesId)
                rows = graph.catalogRepository.episodesFor(last.sourceId, last.providerSeriesId)
            }
            out[last.seriesKey] = rows.map { episode ->
                CatalogEpisode(
                    episodeId = episode.id,
                    season = episode.season,
                    episodeNumber = episode.episodeNumber,
                    title = episode.title,
                    stillUrl = episode.stillUrl,
                    streamUrl = episode.streamUrl,
                )
            }
        }
        return out
    }

    private fun fraction(position: Long, duration: Long): Float =
        if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
}
