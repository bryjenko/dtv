/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.github.bryjenko.dtv.data.PlaybackProgressStore
import io.github.bryjenko.dtv.data.db.DtvDatabase
import io.github.bryjenko.dtv.data.model.PlaybackPosition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Resume rows live in the existing playback_positions table. Two playlists keep separate rows,
 * a finished movie is removed, and a finished episode is kept so My List can offer the next one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class PlaybackProgressStoreTest {

    private lateinit var db: DtvDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, DtvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `two providers keep separate progress for the same profile`() = runBlocking {
        val dao = db.positions()
        PlaybackProgressStore.save(
            dao, profileId = 1, sourceId = 10, mediaKey = "movie:1",
            positionMillis = 60_000, durationMillis = TWO_HOURS, nowMillis = 1,
        )
        PlaybackProgressStore.save(
            dao, profileId = 1, sourceId = 20, mediaKey = "movie:2",
            positionMillis = 120_000, durationMillis = TWO_HOURS, nowMillis = 2,
        )

        assertThat(dao.forProfileAndSource(1, 10).map { it.mediaKey }).containsExactly("movie:1")
        assertThat(dao.forProfileAndSource(1, 20).single().positionMillis).isEqualTo(120_000L)

        PlaybackProgressStore.save(
            dao, profileId = 1, sourceId = 10, mediaKey = "movie:1",
            positionMillis = 90_000, durationMillis = TWO_HOURS, nowMillis = 3,
        )

        assertThat(dao.forProfileAndSource(1, 20).single().positionMillis).isEqualTo(120_000L)
        assertThat(dao.get(1, "movie:1")!!.sourceId).isEqualTo(10L)
        assertThat(dao.get(1, "movie:1")!!.positionMillis).isEqualTo(90_000L)
    }

    @Test
    fun `a finished movie is removed and a finished episode stays`() = runBlocking {
        val dao = db.positions()
        val almostDone = FORTY_MINUTES - 60_000L
        PlaybackProgressStore.save(
            dao, profileId = 1, sourceId = 10, mediaKey = "movie:5",
            positionMillis = almostDone, durationMillis = FORTY_MINUTES, nowMillis = 1,
        )
        PlaybackProgressStore.save(
            dao, profileId = 1, sourceId = 10, mediaKey = "ep:8",
            positionMillis = almostDone, durationMillis = FORTY_MINUTES, nowMillis = 2,
        )

        assertThat(dao.get(1, "movie:5")).isNull()
        val episode = dao.get(1, "ep:8")
        assertThat(episode).isNotNull()
        assertThat(episode!!.isFinished).isTrue()
        assertThat(episode.sourceId).isEqualTo(10L)
    }

    @Test
    fun `removing one show does not remove another provider's row`() = runBlocking {
        val dao = db.positions()
        dao.upsert(position(sourceId = 10, key = "ep:1"))
        dao.upsert(position(sourceId = 20, key = "ep:2"))
        dao.upsert(position(sourceId = 10, key = "ep:3"))

        PlaybackProgressStore.deleteKeys(dao, profileId = 1, keys = listOf("ep:1", "ep:3"))

        val left = dao.forProfile(1)
        assertThat(left.map { it.mediaKey }).containsExactly("ep:2")
        assertThat(left.single().sourceId).isEqualTo(20L)
    }

    @Test
    fun `a position under five seconds is not stored`() = runBlocking {
        val dao = db.positions()
        PlaybackProgressStore.save(
            dao, profileId = 1, sourceId = 10, mediaKey = "movie:1",
            positionMillis = 2_000, durationMillis = TWO_HOURS, nowMillis = 1,
        )
        assertThat(dao.forProfile(1)).isEmpty()
    }

    private fun position(sourceId: Long, key: String) = PlaybackPosition(
        profileId = 1,
        mediaKey = key,
        positionMillis = 60_000,
        durationMillis = TWO_HOURS,
        updatedAtMillis = sourceId,
        sourceId = sourceId,
    )

    private companion object {
        const val TWO_HOURS = 2L * 60L * 60L * 1000L
        const val FORTY_MINUTES = 40L * 60L * 1000L
    }
}
