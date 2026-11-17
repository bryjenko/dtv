/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv.ui.channels

/**
 * A remote key the search field may have to handle itself.
 *
 * [Center] is D-pad OK. It submits only while the on-screen keyboard is up, which is when a
 * keyboard's confirm key is forwarded into the field. With the keyboard closed, OK opens it
 * again — otherwise a remote could never start typing. [Enter] always submits.
 */
internal enum class SearchFieldKey {
    Left,
    Right,
    Up,
    Down,
    Enter,
    Center,
    Back,
}

/** Where focus should step when it leaves the search field. */
internal enum class SearchFieldMove {
    Left,
    Right,
    Up,
    Down,
}

/**
 * What the search field should do with one remote key.
 *
 * Touch and phone input never asks: the screen only consults this on a television, so a
 * handset keeps an ordinary text field.
 */
internal sealed interface SearchFieldCommand {
    /** The text field keeps the key (caret movement, or the platform default). */
    data object Ignore : SearchFieldCommand

    /**
     * Move to the neighbour in [direction] and hide the keyboard.
     *
     * [toResults] is the DOWN / submit case: land on the first result, or on the next
     * focusable control when there are no results.
     */
    data class Leave(val direction: SearchFieldMove, val toResults: Boolean) : SearchFieldCommand

    /** Hide the keyboard, drop focus, and land on the first result or the next focusable. */
    data object Submit : SearchFieldCommand

    /** Hide the keyboard and keep the field focused, so the next BACK can leave the screen. */
    data object DismissKeyboard : SearchFieldCommand

    /** The keyboard is closed; OK should bring it back rather than jumping to the results. */
    data object ShowKeyboard : SearchFieldCommand
}

/**
 * Decides how the search field handles one remote key while it is focused.
 *
 * LEFT and RIGHT still move the caret when the field has text and the caret is not already
 * on that edge. A selection that isn't a bare caret is left to the text field as well. DOWN
 * always heads for the results. BACK closes the keyboard before it is allowed to leave.
 */
internal fun decideSearchFieldKey(
    key: SearchFieldKey,
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    keyboardOpen: Boolean,
): SearchFieldCommand {
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(0, text.length)
    return when (key) {
        SearchFieldKey.Down -> SearchFieldCommand.Leave(SearchFieldMove.Down, toResults = true)
        SearchFieldKey.Up -> SearchFieldCommand.Leave(SearchFieldMove.Up, toResults = false)
        SearchFieldKey.Left -> horizontal(text, start, end, atEdge = start == 0, SearchFieldMove.Left)
        SearchFieldKey.Right -> horizontal(text, start, end, atEdge = end == text.length, SearchFieldMove.Right)
        SearchFieldKey.Enter -> SearchFieldCommand.Submit
        SearchFieldKey.Center -> if (keyboardOpen) SearchFieldCommand.Submit else SearchFieldCommand.ShowKeyboard
        SearchFieldKey.Back -> if (keyboardOpen) SearchFieldCommand.DismissKeyboard else SearchFieldCommand.Ignore
    }
}

/**
 * LEFT/RIGHT leave the field only at the edge, or when there is no text to move through.
 * An uncollapsed selection stays with the text field so the remote can still adjust it.
 */
private fun horizontal(
    text: String,
    start: Int,
    end: Int,
    atEdge: Boolean,
    direction: SearchFieldMove,
): SearchFieldCommand {
    if (start != end) return SearchFieldCommand.Ignore
    if (text.isNotEmpty() && !atEdge) return SearchFieldCommand.Ignore
    return SearchFieldCommand.Leave(direction, toResults = false)
}
