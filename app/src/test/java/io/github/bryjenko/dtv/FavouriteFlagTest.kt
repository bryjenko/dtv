/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.github.bryjenko.dtv.data.db.DtvDatabase
import io.github.bryjenko.dtv.data.model.Movie
import io.github.bryjenko.dtv.data.model.Series
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The star on a film or show writes the existing favourite column. My List reads that column.
 * The same provider id on a second playlist is a different row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class FavouriteFlagTest {

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
    fun `starring a film on one playlist leaves the other playlist alone`() = runBlocking {
        val dao = db.movies()
        dao.upsertAll(
            listOf(
                movie(sourceId = 1, streamId = "9", url = "http://a/9", name = "On A"),
                movie(sourceId = 2, streamId = "9", url = "http://b/9", name = "On B"),
            ),
        )
        val onA = dao.byStreamUrl("http://a/9")!!
        val onB = dao.byStreamUrl("http://b/9")!!

        dao.setFavourite(onA.id, true)

        val starred = dao.observeFavourites().first()
        assertThat(starred.map { it.id }).containsExactly(onA.id)
        assertThat(starred.single().sourceId).isEqualTo(1L)
        assertThat(dao.byStreamUrl("http://b/9")!!.favourite).isFalse()

        dao.setFavourite(onA.id, false)

        assertThat(dao.observeFavourites().first()).isEmpty()
        assertThat(dao.byId(onB.id)!!.favourite).isFalse()
    }

    @Test
    fun `starring a show on one playlist leaves the other playlist alone`() = runBlocking {
        val dao = db.series()
        dao.upsertAll(
            listOf(
                series(sourceId = 1, seriesId = "100", name = "On A"),
                series(sourceId = 2, seriesId = "100", name = "On B"),
            ),
        )
        val onA = dao.bySourceAndSeriesId(1, "100")!!
        val onB = dao.bySourceAndSeriesId(2, "100")!!

        dao.setFavourite(onA.id, true)

        val starred = dao.observeFavourites().first()
        assertThat(starred.map { it.id }).containsExactly(onA.id)
        assertThat(starred.single().sourceId).isEqualTo(1L)
        assertThat(starred.single().seriesId).isEqualTo("100")
        assertThat(dao.bySourceAndSeriesId(2, "100")!!.favourite).isFalse()

        dao.setFavourite(onA.id, false)

        assertThat(dao.observeFavourites().first()).isEmpty()
        assertThat(dao.byId(onB.id)!!.favourite).isFalse()
    }

    @Test
    fun `a catalogue refresh keeps the star on that film`() = runBlocking {
        val dao = db.movies()
        dao.upsertAll(listOf(movie(sourceId = 1, streamId = "9", url = "http://a/9", name = "Alpha")))
        val stored = dao.byStreamUrl("http://a/9")!!
        dao.setFavourite(stored.id, true)

        dao.upsertAll(listOf(movie(sourceId = 1, streamId = "9", url = "http://a/9", name = "Alpha HD")))

        assertThat(dao.count()).isEqualTo(1)
        val again = dao.byStreamUrl("http://a/9")!!
        assertThat(again.id).isEqualTo(stored.id)
        assertThat(again.favourite).isTrue()
    }

    @Test
    fun `a catalogue refresh keeps the star on that show`() = runBlocking {
        val dao = db.series()
        dao.upsertAll(listOf(series(sourceId = 1, seriesId = "100", name = "Show")))
        val stored = dao.bySourceAndSeriesId(1, "100")!!
        dao.setFavourite(stored.id, true)

        dao.upsertAll(listOf(series(sourceId = 1, seriesId = "100", name = "Show (HD)")))

        assertThat(dao.count()).isEqualTo(1)
        val again = dao.bySourceAndSeriesId(1, "100")!!
        assertThat(again.id).isEqualTo(stored.id)
        assertThat(again.favourite).isTrue()
    }

    private fun movie(sourceId: Long, streamId: String, url: String, name: String) = Movie(
        sourceId = sourceId,
        streamId = streamId,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = url,
    )

    private fun series(sourceId: Long, seriesId: String, name: String) = Series(
        sourceId = sourceId,
        seriesId = seriesId,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
    )
}
