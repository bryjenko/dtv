/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember

/** A short remote OK selects; holding it favours. Touch users keep tap and long-press behaviour. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.remoteSelectOrLongSelect(
    onSelect: () -> Unit,
    onLongSelect: () -> Unit,
): Modifier = composed {
    val selectStartedAt = remember { mutableLongStateOf(0L) }
    onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionCenter &&
            event.key != Key.Enter &&
            event.key != Key.NumPadEnter
        ) {
            return@onPreviewKeyEvent false
        }

        when (event.type) {
            KeyEventType.KeyDown -> {
                if (selectStartedAt.longValue == 0L) selectStartedAt.longValue = SystemClock.uptimeMillis()
                true
            }
            KeyEventType.KeyUp -> {
                val heldFor = if (selectStartedAt.longValue == 0L) 0L else SystemClock.uptimeMillis() - selectStartedAt.longValue
                selectStartedAt.longValue = 0L
                if (heldFor >= LONG_SELECT_MILLIS) onLongSelect() else onSelect()
                true
            }
            else -> false
        }
    }.combinedClickable(onClick = onSelect, onLongClick = onLongSelect)
}

private const val LONG_SELECT_MILLIS = 550L
