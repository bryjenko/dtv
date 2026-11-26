/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv

import com.google.common.truth.Truth.assertThat
import io.github.bryjenko.dtv.data.model.Channel
import io.github.bryjenko.dtv.data.repo.mergeCatalogueChannels
import org.junit.Test

class CatalogueMergeTest {

    @Test
    fun `first sync keeps the provider order`() {
        val incoming = listOf(ch(streamId = "a", sortIndex = 2), ch(streamId = "b", sortIndex = 5))
        val merged = mergeCatalogueChannels(emptyMap(), incoming, syncStamp = 1000L)
        assertThat(merged.map { it.streamId to it.sortIndex }).containsExactly("a" to 2, "b" to 5).inOrder()
        assertThat(merged.map { it.lastSeenMillis }).containsExactly(1000L, 1000L)
    }

    @Test
    fun `preserves favourites hidden order rename and ids`() {
        val existing = ch(
            id = 42,
            streamId = "bbc1",
            sortIndex = 7,
            favourite = true,
            hidden = true,
            customName = "BBC",
            epgOverrideId = "override",
            matchedEpgId = "matched",
        )
        val incoming = ch(streamId = "bbc1", sortIndex = 0, name = "UK| BBC ONE FHD")
        val merged = mergeCatalogueChannels(mapOf("bbc1" to existing), listOf(incoming), 2000L).single()
        assertThat(merged.id).isEqualTo(42)
        assertThat(merged.favourite).isTrue()
        assertThat(merged.hidden).isTrue()
        assertThat(merged.sortIndex).isEqualTo(7)
        assertThat(merged.customName).isEqualTo("BBC")
        assertThat(merged.epgOverrideId).isEqualTo("override")
        assertThat(merged.matchedEpgId).isEqualTo("matched")
        assertThat(merged.name).isEqualTo("UK| BBC ONE FHD")
        assertThat(merged.lastSeenMillis).isEqualTo(2000L)
    }

    @Test
    fun `separator hide is kept even if the user had not hidden the row`() {
        val existing = ch(streamId = "sep", hidden = false)
        val incoming = ch(streamId = "sep", hidden = true)
        val merged = mergeCatalogueChannels(mapOf("sep" to existing), listOf(incoming), 1L).single()
        assertThat(merged.hidden).isTrue()
    }

    @Test
    fun `new channels are appended after the current last sort index`() {
        val existing = mapOf(
            "old-a" to ch(streamId = "old-a", sortIndex = 3),
            "old-b" to ch(streamId = "old-b", sortIndex = 10),
        )
        val incoming = listOf(
            ch(streamId = "old-a", sortIndex = 0),
            ch(streamId = "new-1", sortIndex = 1),
            ch(streamId = "new-2", sortIndex = 2),
        )
        val merged = mergeCatalogueChannels(existing, incoming, 3L)
        assertThat(merged.associate { it.streamId to it.sortIndex }).containsExactly(
            "old-a", 3,
            "new-1", 11,
            "new-2", 12,
        )
    }

    private fun ch(
        streamId: String,
        id: Long = 0,
        sortIndex: Int = 0,
        favourite: Boolean = false,
        hidden: Boolean = false,
        customName: String? = null,
        name: String = "Channel $streamId",
        epgOverrideId: String? = null,
        matchedEpgId: String? = null,
    ) = Channel(
        id = id,
        sourceId = 1,
        streamId = streamId,
        name = name,
        categoryId = "news",
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://example/stream",
        favourite = favourite,
        hidden = hidden,
        sortIndex = sortIndex,
        customName = customName,
        epgOverrideId = epgOverrideId,
        matchedEpgId = matchedEpgId,
    )
}
