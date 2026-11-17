/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv.ui.channels

import android.content.Context
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bryjenko.dtv.R
import io.github.bryjenko.dtv.data.model.Channel
import io.github.bryjenko.dtv.data.model.Movie
import io.github.bryjenko.dtv.data.model.Series
import io.github.bryjenko.dtv.data.model.shownName
import io.github.bryjenko.dtv.data.parser.displayTitle
import io.github.bryjenko.dtv.isRunningOnTelevision
import io.github.bryjenko.dtv.ui.ChannelsViewModel
import io.github.bryjenko.dtv.ui.VodViewModel
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Search across live channels, movies and series. One field covers all three: the rail's Search
 * entry and the Movies / Shows affordance both open this screen.
 *
 * On a television the text field swallows the D-pad, so a remote can type a query and then never
 * reach a result. Arrows therefore leave the field (DOWN into the results), Search/Done/Go and
 * Enter submit into the results, and the first BACK only closes the keyboard. Phones are left
 * on the ordinary text field.
 */
@Composable
fun SearchScreen(
    onPlayChannel: (Channel) -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onBack: () -> Unit,
    viewModel: ChannelsViewModel = viewModel(),
    vodViewModel: VodViewModel = viewModel(),
) {
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val searchFocusRequester = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val view = LocalView.current
    val isTelevision = remember(context) { isRunningOnTelevision(context) }
    val channelResults by viewModel.searchResults.collectAsState()
    val movieResults by vodViewModel.movieResults.collectAsState()
    val seriesResults by vodViewModel.seriesResults.collectAsState()
    val anyResults = channelResults.isNotEmpty() || movieResults.isNotEmpty() || seriesResults.isNotEmpty()
    // Television keyboards are often a floating panel that never reports IME insets, so "the
    // keyboard is up" starts as "the field is focused" and stays that way until BACK or a focus
    // exit hides it. When a device does report insets, a visible-to-hidden transition means the
    // system already closed the keyboard, and the next BACK may leave.
    var fieldFocused by remember { mutableStateOf(false) }
    var imeDismissed by remember { mutableStateOf(false) }
    var imeWasInsetVisible by remember { mutableStateOf(false) }
    var dismissedImeAt by remember { mutableLongStateOf(0L) }
    val imeInsetVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    SideEffect {
        if (imeWasInsetVisible && !imeInsetVisible) imeDismissed = true
        imeWasInsetVisible = imeInsetVisible
    }
    val keyboardOpen = fieldFocused && !imeDismissed

    fun hideIme() {
        imeDismissed = true
        keyboardController?.hide()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun showIme() {
        imeDismissed = false
        keyboardController?.show()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    fun moveFocus(direction: FocusDirection): Boolean =
        runCatching { focusManager.moveFocus(direction) }.getOrDefault(false)

    fun moveFocus(direction: SearchFieldMove): Boolean = moveFocus(
        when (direction) {
            SearchFieldMove.Left -> FocusDirection.Left
            SearchFieldMove.Right -> FocusDirection.Right
            SearchFieldMove.Up -> FocusDirection.Up
            SearchFieldMove.Down -> FocusDirection.Down
        },
    )

    // Land on the first result when there is one. Otherwise step to whatever can take focus
    // (the Done button, on this screen) and, failing that, drop focus so the field lets go.
    fun focusResultsOrNext(): Boolean {
        if (anyResults && firstResultFocus.tryRequestFocus()) return true
        if (moveFocus(FocusDirection.Down)) return true
        if (moveFocus(FocusDirection.Right)) return true
        if (moveFocus(FocusDirection.Next)) return true
        focusManager.clearFocus(force = true)
        return true
    }

    fun submitSearch() {
        hideIme()
        focusResultsOrNext()
    }

    LaunchedEffect(field.text) {
        viewModel.setSearchQuery(field.text)
        vodViewModel.setVodSearchQuery(field.text)
    }
    LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }
    BackHandler {
        // A key handler that already closed the keyboard for this same press stamps the clock.
        // Ignoring that callback keeps the first BACK from also popping the screen.
        if (isTelevision && SystemClock.uptimeMillis() - dismissedImeAt < SAME_PRESS_WINDOW_MILLIS) return@BackHandler
        if (isTelevision && keyboardOpen) {
            dismissedImeAt = SystemClock.uptimeMillis()
            hideIme()
        } else {
            onBack()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_search), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(20.dp))
            OutlinedTextField(
                value = field,
                onValueChange = { field = it.limitedTo(SEARCH_QUERY_LIMIT) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocusRequester)
                    .onFocusChanged { state ->
                        fieldFocused = state.isFocused
                        // Arriving on the field brings the keyboard back. A hide that briefly
                        // blurs and refocuses the field must not undo the BACK that just closed it.
                        val justDismissed = SystemClock.uptimeMillis() - dismissedImeAt < SAME_PRESS_WINDOW_MILLIS
                        if (state.isFocused && !justDismissed) {
                            imeDismissed = false
                            if (isTelevision) showIme()
                        }
                    }
                    .then(
                        if (isTelevision) {
                            Modifier
                                .focusProperties { if (anyResults) down = firstResultFocus }
                                .onPreviewKeyEvent { event ->
                                    val mapped = event.key.toSearchFieldKey() ?: return@onPreviewKeyEvent false
                                    val command = decideSearchFieldKey(
                                        key = mapped,
                                        text = field.text,
                                        selectionStart = field.selection.start,
                                        selectionEnd = field.selection.end,
                                        keyboardOpen = keyboardOpen,
                                    )
                                    if (command is SearchFieldCommand.Ignore) return@onPreviewKeyEvent false
                                    if (mapped == SearchFieldKey.Back) {
                                        // Stamp down and up. The back dispatcher can run for either,
                                        // and it must not treat this press as "leave the screen".
                                        dismissedImeAt = SystemClock.uptimeMillis()
                                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                            hideIme()
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                    val initialDown = event.type == KeyEventType.KeyDown &&
                                        event.nativeKeyEvent.repeatCount == 0
                                    if (!initialDown) {
                                        return@onPreviewKeyEvent event.type == KeyEventType.KeyUp ||
                                            event.nativeKeyEvent.repeatCount > 0
                                    }
                                    when (command) {
                                        SearchFieldCommand.Ignore -> return@onPreviewKeyEvent false
                                        is SearchFieldCommand.Leave -> {
                                            val moved = if (command.toResults) {
                                                focusResultsOrNext()
                                            } else {
                                                moveFocus(command.direction)
                                            }
                                            if (moved) hideIme()
                                        }
                                        SearchFieldCommand.Submit -> submitSearch()
                                        SearchFieldCommand.DismissKeyboard -> hideIme()
                                        SearchFieldCommand.ShowKeyboard -> showIme()
                                    }
                                    true
                                }
                        } else {
                            Modifier
                        },
                    ),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_type_name)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                // Phones keep the previous behaviour: Search only hides the keyboard. A television
                // keyboard may report Search, Done or Go depending on the input method.
                keyboardActions = if (isTelevision) {
                    KeyboardActions(
                        onSearch = { submitSearch() },
                        onDone = { submitSearch() },
                        onGo = { submitSearch() },
                    )
                } else {
                    KeyboardActions(onSearch = { keyboardController?.hide() })
                },
            )
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                when {
                    field.text.isBlank() -> Hint(stringResource(R.string.search_start_hint))
                    field.text.trim().length < 2 -> Hint(stringResource(R.string.common_keep_typing))
                    !anyResults -> Hint(stringResource(R.string.search_no_results, field.text))
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val firstResultKey = when {
                            channelResults.isNotEmpty() -> "c${channelResults.first().key}"
                            movieResults.isNotEmpty() -> "m${movieResults.first().id}"
                            seriesResults.isNotEmpty() -> "s${seriesResults.first().id}"
                            else -> null
                        }
                        if (channelResults.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.common_channels)) }
                            items(channelResults, key = { "c${it.key}" }) { row ->
                                val key = "c${row.key}"
                                SearchResultRow(
                                    row = row,
                                    onClick = { onPlayChannel(row.primary) },
                                    modifier = if (key == firstResultKey) Modifier.focusRequester(firstResultFocus) else Modifier,
                                )
                            }
                        }
                        if (movieResults.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.nav_movies)) }
                            items(movieResults, key = { "m${it.id}" }) { movie ->
                                val key = "m${movie.id}"
                                VodResultRow(
                                    name = movie.displayTitle,
                                    posterUrl = movie.posterUrl,
                                    subtitle = movie.year?.toString(),
                                    onClick = { onPlayMovie(movie) },
                                    modifier = if (key == firstResultKey) Modifier.focusRequester(firstResultFocus) else Modifier,
                                )
                            }
                        }
                        if (seriesResults.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.nav_shows)) }
                            items(seriesResults, key = { "s${it.id}" }) { show ->
                                val key = "s${show.id}"
                                VodResultRow(
                                    name = show.displayTitle,
                                    posterUrl = show.posterUrl,
                                    subtitle = show.year?.toString(),
                                    onClick = { onOpenSeries(show) },
                                    modifier = if (key == firstResultKey) Modifier.focusRequester(firstResultFocus) else Modifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun VodResultRow(
    name: String,
    posterUrl: String?,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = posterUrl,
            contentDescription = null,
            modifier = Modifier.size(width = 34.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    row: ChannelsViewModel.Row,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = row.primary.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.primary.shownName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.now?.let { "${searchTime(it.startUtcMillis)}  ${it.title}" } ?: stringResource(R.string.guide_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val searchTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun searchTime(utcMillis: Long): String = searchTimeFormat.format(Date(utcMillis))

/** The query box has always capped what you can type. Clipping keeps the caret inside the text. */
private fun TextFieldValue.limitedTo(maxChars: Int): TextFieldValue {
    if (text.length <= maxChars) return this
    val clipped = text.take(maxChars)
    return TextFieldValue(text = clipped, selection = TextRange(clipped.length))
}

/** `requestFocus` throws until the result row has been composed. */
private fun FocusRequester.tryRequestFocus(): Boolean = try {
    requestFocus()
    true
} catch (_: IllegalStateException) {
    false
}

private fun Key.toSearchFieldKey(): SearchFieldKey? = when (this) {
    Key.DirectionLeft -> SearchFieldKey.Left
    Key.DirectionRight -> SearchFieldKey.Right
    Key.DirectionUp -> SearchFieldKey.Up
    Key.DirectionDown -> SearchFieldKey.Down
    Key.Enter, Key.NumPadEnter -> SearchFieldKey.Enter
    Key.DirectionCenter -> SearchFieldKey.Center
    Key.Back, Key.Escape -> SearchFieldKey.Back
    else -> null
}

/** Long enough to cover a key-up dispatched just after we hid the keyboard, not a second press. */
private const val SAME_PRESS_WINDOW_MILLIS = 120L

private const val SEARCH_QUERY_LIMIT = 40
