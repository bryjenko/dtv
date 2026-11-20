/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv.ui.mylist

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.bryjenko.dtv.R
import io.github.bryjenko.dtv.data.model.Channel
import io.github.bryjenko.dtv.data.model.Movie
import io.github.bryjenko.dtv.data.model.Series
import io.github.bryjenko.dtv.data.model.shownName
import io.github.bryjenko.dtv.data.parser.displayTitle
import io.github.bryjenko.dtv.ui.channels.remoteSelectOrLongSelect
import kotlinx.coroutines.delay

/**
 * My List, in the left-hand rail under Recordings.
 *
 * Groups, top to bottom, and only when that group has something in it: movies still in progress,
 * one card per TV series, starred films, starred shows, and favourite live channels. OK (or a tap)
 * resumes, opens the title, or tunes. Holding OK, or the Menu key, opens the same actions as
 * before, including Remove from My List. An empty page says so once.
 */
@Composable
fun MyListScreen(
    onPlayChannel: (Channel) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    viewModel: MyListViewModel = viewModel(),
) {
    val content by viewModel.content.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val favouriteMovies by viewModel.favouriteMovies.collectAsState()
    val favouriteShows by viewModel.favouriteShows.collectAsState()
    var menu by remember { mutableStateOf<MyListMenu?>(null) }
    val movies = content.movies
    val shows = content.shows
    val empty = movies.isEmpty() && shows.isEmpty() && channels.isEmpty() &&
        favouriteMovies.isEmpty() && favouriteShows.isEmpty()

    if (empty) {
        EmptyMyList()
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.nav_my_list),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                )
                Text(
                    stringResource(R.string.mylist_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                )
            }
            if (movies.isNotEmpty()) {
                item(key = "movies-header") { GroupHeader(stringResource(R.string.mylist_movies)) }
                item(key = "movies-row") {
                    CardRow {
                        items(movies, key = { it.mediaKey }) { item ->
                            ProgressCard(
                                title = item.title,
                                imageUrl = item.posterUrl,
                                subtitle = null,
                                progress = item.progress,
                                badge = null,
                                onClick = { onResume(item.mediaKey, item.streamUrl, item.title) },
                                onMenu = { menu = MyListMenu.ContinueMovie(item) },
                            )
                        }
                    }
                }
            }
            if (shows.isNotEmpty()) {
                item(key = "shows-header") { GroupHeader(stringResource(R.string.mylist_shows)) }
                item(key = "shows-row") {
                    CardRow {
                        items(shows, key = { it.seriesKey }) { item ->
                            val subtitle = if (item.playingNext) {
                                stringResource(R.string.mylist_next_episode, item.season, item.episodeNumber)
                            } else {
                                episodeLabel(item.season, item.episodeNumber, item.episodeTitle)
                            }
                            ProgressCard(
                                title = item.seriesTitle,
                                imageUrl = item.posterUrl,
                                subtitle = subtitle,
                                progress = if (item.playingNext) null else item.progress,
                                badge = if (item.playingNext) stringResource(R.string.mylist_next_badge) else null,
                                onClick = {
                                    onResume(item.playMediaKey, item.playStreamUrl, playTitle(item))
                                },
                                onMenu = { menu = MyListMenu.ContinueShow(item) },
                            )
                        }
                    }
                }
            }
            if (favouriteShows.isNotEmpty()) {
                item(key = "fav-shows-header") { GroupHeader(stringResource(R.string.mylist_favourite_shows)) }
                item(key = "fav-shows-row") {
                    CardRow {
                        items(favouriteShows, key = { it.series.id }) { item ->
                            val resume = item.resume
                            ProgressCard(
                                title = item.series.displayTitle,
                                imageUrl = item.series.posterUrl ?: item.series.backdropUrl,
                                subtitle = resume?.let {
                                    episodeLabel(it.season, it.episodeNumber, it.episodeTitle)
                                },
                                progress = resume?.progress,
                                badge = null,
                                onClick = {
                                    if (resume != null) {
                                        onResume(resume.mediaKey, resume.streamUrl, favouriteShowPlayTitle(item))
                                    } else {
                                        onOpenSeries(item.series)
                                    }
                                },
                                onMenu = { menu = MyListMenu.FavouriteShow(item) },
                            )
                        }
                    }
                }
            }
            if (favouriteMovies.isNotEmpty()) {
                item(key = "fav-movies-header") { GroupHeader(stringResource(R.string.mylist_favourite_movies)) }
                item(key = "fav-movies-row") {
                    CardRow {
                        items(favouriteMovies, key = { it.id }) { movie ->
                            ProgressCard(
                                title = movie.displayTitle,
                                imageUrl = movie.posterUrl ?: movie.backdropUrl,
                                subtitle = null,
                                progress = null,
                                badge = null,
                                onClick = { onOpenMovie(movie) },
                                onMenu = { menu = MyListMenu.FavouriteMovie(movie) },
                            )
                        }
                    }
                }
            }
            if (channels.isNotEmpty()) {
                item(key = "channels-header") { GroupHeader(stringResource(R.string.mylist_channels)) }
                items(channels, key = { "ch-${it.id}" }) { channel ->
                    FavouriteChannelRow(
                        channel = channel,
                        onClick = { onPlayChannel(channel) },
                        onMenu = { menu = MyListMenu.Favourite(channel) },
                    )
                }
            }
        }
    }

    menu?.let { target ->
        MyListActionDialog(
            target = target,
            onResume = { key, url, title ->
                menu = null
                onResume(key, url, title)
            },
            onStartOver = { key, url, title ->
                menu = null
                viewModel.playFromStart(key) { onResume(key, url, title) }
            },
            onRemove = {
                when (target) {
                    is MyListMenu.ContinueMovie -> viewModel.removeMovie(target.item.mediaKey)
                    is MyListMenu.ContinueShow -> viewModel.removeShow(target.item)
                    is MyListMenu.Favourite -> viewModel.unfavourite(target.channel)
                    is MyListMenu.FavouriteMovie -> viewModel.unfavouriteMovie(target.movie)
                    is MyListMenu.FavouriteShow -> viewModel.unfavouriteShow(target.item.series)
                }
                menu = null
            },
            onDismiss = { menu = null },
        )
    }
}

private sealed interface MyListMenu {
    data class ContinueMovie(val item: MyListViewModel.MovieItem) : MyListMenu
    data class ContinueShow(val item: MyListViewModel.ShowItem) : MyListMenu
    data class Favourite(val channel: Channel) : MyListMenu
    data class FavouriteMovie(val movie: Movie) : MyListMenu
    data class FavouriteShow(val item: MyListViewModel.FavouriteShow) : MyListMenu
}

/** Same horizontal strip the continue-watching cards use, so D-pad focus moves the same way. */
@Composable
private fun CardRow(content: LazyListScope.() -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun EmptyMyList() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.mylist_empty_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.mylist_empty_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Landscape card used for both a movie and a series. Focus scales it and draws the primary
 * border the rest of the app uses, so a D-pad highlight is obvious on a TV and a tap works
 * on a phone. Holding OK or pressing Menu opens the actions.
 */
@Composable
private fun ProgressCard(
    title: String,
    imageUrl: String?,
    subtitle: String?,
    progress: Float?,
    badge: String?,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "myListCard")
    Column(
        Modifier
            .width(200.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                    onMenu()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged { focused = it.hasFocus }
            .remoteSelectOrLongSelect(onSelect = onClick, onLongSelect = onMenu)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    else Modifier,
                ),
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            badge?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            progress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FavouriteChannelRow(
    channel: Channel,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                    onMenu()
                    true
                } else {
                    false
                }
            }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .onFocusChanged { focused = it.hasFocus }
            .remoteSelectOrLongSelect(onSelect = onClick, onLongSelect = onMenu)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = channel.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            channel.shownName,
            style = MaterialTheme.typography.titleMedium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.Star,
            contentDescription = stringResource(R.string.common_favourite),
            tint = if (focused) fg else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MyListActionDialog(
    target: MyListMenu,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    onStartOver: (mediaKey: String, url: String, title: String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resumeLabel = stringResource(R.string.vod_resume)
    val startOverLabel = stringResource(R.string.mylist_start_over)
    val playNextLabel = stringResource(R.string.mylist_play_next)
    val watchAgainLabel = stringResource(R.string.mylist_watch_again)
    val removeLabel = stringResource(R.string.mylist_remove)
    val removeFavouriteLabel = stringResource(R.string.common_remove_favourite)
    val cancelLabel = stringResource(R.string.common_cancel)

    val title: String
    val body: String?
    val actions: List<Pair<String, () -> Unit>>
    when (target) {
        is MyListMenu.ContinueMovie -> {
            val item = target.item
            title = item.title
            body = null
            actions = listOf(
                resumeLabel to { onResume(item.mediaKey, item.streamUrl, item.title) },
                startOverLabel to { onStartOver(item.mediaKey, item.streamUrl, item.title) },
                removeLabel to onRemove,
                cancelLabel to onDismiss,
            )
        }
        is MyListMenu.ContinueShow -> {
            val item = target.item
            title = item.seriesTitle
            body = episodeLabel(item.season, item.episodeNumber, item.episodeTitle)
            actions = if (item.playingNext) {
                listOf(
                    playNextLabel to { onResume(item.playMediaKey, item.playStreamUrl, playTitle(item)) },
                    watchAgainLabel to {
                        // A finished episode already resumes at the start. Leave the saved row so
                        // the series stays on My List if they back out straight away.
                        onResume(item.watchedMediaKey, item.watchedStreamUrl, item.seriesTitle)
                    },
                    removeLabel to onRemove,
                    cancelLabel to onDismiss,
                )
            } else {
                listOf(
                    resumeLabel to { onResume(item.playMediaKey, item.playStreamUrl, playTitle(item)) },
                    startOverLabel to {
                        onStartOver(item.playMediaKey, item.playStreamUrl, playTitle(item))
                    },
                    removeLabel to onRemove,
                    cancelLabel to onDismiss,
                )
            }
        }
        is MyListMenu.Favourite -> {
            title = target.channel.shownName
            body = null
            actions = listOf(
                removeFavouriteLabel to onRemove,
                cancelLabel to onDismiss,
            )
        }
        is MyListMenu.FavouriteMovie -> {
            title = target.movie.displayTitle
            body = null
            actions = listOf(
                removeLabel to onRemove,
                cancelLabel to onDismiss,
            )
        }
        is MyListMenu.FavouriteShow -> {
            val item = target.item
            val resume = item.resume
            title = item.series.displayTitle
            body = resume?.let { episodeLabel(it.season, it.episodeNumber, it.episodeTitle) }
            actions = buildList {
                if (resume != null) {
                    add(resumeLabel to {
                        onResume(resume.mediaKey, resume.streamUrl, favouriteShowPlayTitle(item))
                    })
                }
                add(removeLabel to onRemove)
                add(cancelLabel to onDismiss)
            }
        }
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(target) {
        delay(40)
        runCatching { firstFocus.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (body != null) {
                Spacer(Modifier.height(6.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            actions.forEachIndexed { index, (label, onClick) ->
                DialogAction(label, if (index == 0) firstFocus else null, onClick)
                if (index != actions.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DialogAction(label: String, focusRequester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.hasFocus }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .remoteSelectOrLongSelect(onSelect = onClick, onLongSelect = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

private fun episodeLabel(season: Int, episode: Int, title: String): String {
    val code = "S$season E$episode"
    val name = title.trim()
    return if (name.isEmpty() || name.equals(code, ignoreCase = true)) code else "$code · $name"
}

private fun playTitle(item: MyListViewModel.ShowItem): String {
    val episode = episodeLabel(item.season, item.episodeNumber, item.episodeTitle)
    return if (item.seriesTitle.isBlank()) episode else "${item.seriesTitle} · $episode"
}

private fun favouriteShowPlayTitle(item: MyListViewModel.FavouriteShow): String {
    val resume = item.resume ?: return item.series.displayTitle
    val episode = episodeLabel(resume.season, resume.episodeNumber, resume.episodeTitle)
    val name = item.series.displayTitle
    return if (name.isBlank()) episode else "$name · $episode"
}
