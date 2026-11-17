/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv

import com.google.common.truth.Truth.assertThat
import io.github.bryjenko.dtv.ui.channels.SearchFieldCommand
import io.github.bryjenko.dtv.ui.channels.SearchFieldKey
import io.github.bryjenko.dtv.ui.channels.SearchFieldMove
import io.github.bryjenko.dtv.ui.channels.decideSearchFieldKey
import org.junit.Test

/**
 * Remote-key policy for the shared search field. The composable applies this only on a
 * television; these cases pin the rules a D-pad has to follow so a later edit can't trap
 * focus in the text again.
 */
class SearchFieldNavigationTest {

    @Test
    fun `down always leaves toward the results`() {
        val command = decide(SearchFieldKey.Down, text = "news", cursor = 2)

        assertThat(command).isEqualTo(SearchFieldCommand.Leave(SearchFieldMove.Down, toResults = true))
    }

    @Test
    fun `up leaves the field even when it is empty`() {
        val command = decide(SearchFieldKey.Up, text = "", cursor = 0)

        assertThat(command).isEqualTo(SearchFieldCommand.Leave(SearchFieldMove.Up, toResults = false))
    }

    @Test
    fun `left and right leave an empty field`() {
        assertThat(decide(SearchFieldKey.Left, text = "", cursor = 0))
            .isEqualTo(SearchFieldCommand.Leave(SearchFieldMove.Left, toResults = false))
        assertThat(decide(SearchFieldKey.Right, text = "", cursor = 0))
            .isEqualTo(SearchFieldCommand.Leave(SearchFieldMove.Right, toResults = false))
    }

    @Test
    fun `left and right move the caret when it is not on that edge`() {
        assertThat(decide(SearchFieldKey.Left, text = "news", cursor = 2))
            .isEqualTo(SearchFieldCommand.Ignore)
        assertThat(decide(SearchFieldKey.Right, text = "news", cursor = 2))
            .isEqualTo(SearchFieldCommand.Ignore)
    }

    @Test
    fun `left leaves at the start and right leaves at the end`() {
        assertThat(decide(SearchFieldKey.Left, text = "news", cursor = 0))
            .isEqualTo(SearchFieldCommand.Leave(SearchFieldMove.Left, toResults = false))
        assertThat(decide(SearchFieldKey.Right, text = "news", cursor = 4))
            .isEqualTo(SearchFieldCommand.Leave(SearchFieldMove.Right, toResults = false))
    }

    @Test
    fun `a selection keeps left and right inside the field`() {
        val command = decide(SearchFieldKey.Left, text = "news", selectionStart = 0, selectionEnd = 4)

        assertThat(command).isEqualTo(SearchFieldCommand.Ignore)
    }

    @Test
    fun `enter always submits`() {
        assertThat(decide(SearchFieldKey.Enter, text = "news", cursor = 4, keyboardOpen = true))
            .isEqualTo(SearchFieldCommand.Submit)
        assertThat(decide(SearchFieldKey.Enter, text = "news", cursor = 4, keyboardOpen = false))
            .isEqualTo(SearchFieldCommand.Submit)
    }

    @Test
    fun `d-pad center submits only while the keyboard is open`() {
        assertThat(decide(SearchFieldKey.Center, text = "news", cursor = 4, keyboardOpen = true))
            .isEqualTo(SearchFieldCommand.Submit)
        assertThat(decide(SearchFieldKey.Center, text = "news", cursor = 4, keyboardOpen = false))
            .isEqualTo(SearchFieldCommand.ShowKeyboard)
    }

    @Test
    fun `back dismisses the keyboard first and otherwise does nothing`() {
        assertThat(decide(SearchFieldKey.Back, text = "news", cursor = 4, keyboardOpen = true))
            .isEqualTo(SearchFieldCommand.DismissKeyboard)
        assertThat(decide(SearchFieldKey.Back, text = "news", cursor = 4, keyboardOpen = false))
            .isEqualTo(SearchFieldCommand.Ignore)
    }

    private fun decide(
        key: SearchFieldKey,
        text: String,
        cursor: Int = 0,
        selectionStart: Int = cursor,
        selectionEnd: Int = cursor,
        keyboardOpen: Boolean = true,
    ): SearchFieldCommand = decideSearchFieldKey(
        key = key,
        text = text,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        keyboardOpen = keyboardOpen,
    )
}
