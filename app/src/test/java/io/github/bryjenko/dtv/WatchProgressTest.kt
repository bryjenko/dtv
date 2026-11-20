/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv

import com.google.common.truth.Truth.assertThat
import io.github.bryjenko.dtv.data.model.SaveAction
import io.github.bryjenko.dtv.data.model.WatchProgress
import org.junit.Test

/**
 * Continue Watching treats a title as finished at about 95% or with three minutes left,
 * whichever comes first, and only after playback has actually started.
 */
class WatchProgressTest {

    private val twoHours = 2L * 60L * 60L * 1000L
    private val fortyMinutes = 40L * 60L * 1000L

    @Test
    fun `not started and unknown length are not finished`() {
        assertThat(WatchProgress.isFinished(0L, twoHours)).isFalse()
        assertThat(WatchProgress.isFinished(-1L, twoHours)).isFalse()
        assertThat(WatchProgress.isFinished(60_000L, 0L)).isFalse()
        assertThat(WatchProgress.isFinished(60_000L, -1L)).isFalse()
    }

    @Test
    fun `ninety five percent of a long film is finished`() {
        val atLine = twoHours * WatchProgress.FINISHED_PERCENT / 100
        assertThat(WatchProgress.isFinished(atLine - 1L, twoHours)).isFalse()
        assertThat(WatchProgress.isFinished(atLine, twoHours)).isTrue()
        assertThat(WatchProgress.isFinished(twoHours, twoHours)).isTrue()
        assertThat(WatchProgress.isFinished(twoHours + 5_000L, twoHours)).isTrue()
    }

    @Test
    fun `three minutes left finishes a title even below ninety five percent`() {
        val threeMinutes = WatchProgress.FINISHED_TAIL_MILLIS
        val justInside = fortyMinutes - threeMinutes
        val justOutside = justInside - 1L
        // 37 of 40 minutes is 92.5%, under the fraction, but inside the last three minutes.
        assertThat(justInside * 100).isLessThan(fortyMinutes * WatchProgress.FINISHED_PERCENT)
        assertThat(WatchProgress.isFinished(justOutside, fortyMinutes)).isFalse()
        assertThat(WatchProgress.isFinished(justInside, fortyMinutes)).isTrue()
    }

    @Test
    fun `the opening seconds of a short video are not treated as the ending`() {
        val fourMinutes = 4L * 60L * 1000L
        assertThat(WatchProgress.isFinished(1_000L, fourMinutes)).isFalse()
        assertThat(WatchProgress.isFinished(WatchProgress.MIN_SAVED_POSITION_MILLIS, fourMinutes)).isFalse()
    }

    @Test
    fun `continue watching keeps a started unfinished movie and drops a finished one`() {
        assertThat(WatchProgress.keepOnContinueWatching(1_000L, twoHours)).isFalse()
        assertThat(WatchProgress.keepOnContinueWatching(60_000L, twoHours)).isTrue()
        val almostDone = fortyMinutes - WatchProgress.FINISHED_TAIL_MILLIS
        assertThat(WatchProgress.keepOnContinueWatching(almostDone, fortyMinutes)).isFalse()
    }

    @Test
    fun `a finished movie is deleted and a finished episode is kept`() {
        val done = fortyMinutes - 60_000L
        assertThat(WatchProgress.saveAction("movie", done, fortyMinutes)).isEqualTo(SaveAction.DELETE)
        assertThat(WatchProgress.saveAction("ep", done, fortyMinutes)).isEqualTo(SaveAction.UPSERT)
        assertThat(WatchProgress.saveAction("movie", 60_000L, twoHours)).isEqualTo(SaveAction.UPSERT)
        assertThat(WatchProgress.saveAction("ep", 1_000L, twoHours)).isEqualTo(SaveAction.IGNORE)
        assertThat(WatchProgress.saveAction("rec", 60_000L, twoHours)).isEqualTo(SaveAction.UPSERT)
        assertThat(WatchProgress.saveAction("movie", 60_000L, 0L)).isEqualTo(SaveAction.IGNORE)
    }
}
