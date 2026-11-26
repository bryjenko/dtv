/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv.data.repo

import io.github.bryjenko.dtv.data.model.Channel

/**
 * Merge a freshly fetched catalogue with the channels already on disk.
 *
 * Favourites, hidden flags, manual order, custom names and EPG overrides must survive a
 * refresh — otherwise organising a 20,000-channel list is undone the next time the provider
 * is synced. New channels the provider has added keep their incoming order on a first sync,
 * and are appended after the current last [Channel.sortIndex] on later syncs so a user's
 * arrangement is not shuffled.
 */
internal fun mergeCatalogueChannels(
    existingByStreamId: Map<String, Channel>,
    incoming: List<Channel>,
    syncStamp: Long,
): List<Channel> {
    if (incoming.isEmpty()) return emptyList()
    val hasExisting = existingByStreamId.isNotEmpty()
    var nextAppend = if (hasExisting) existingByStreamId.values.maxOf { it.sortIndex } + 1 else 0
    return incoming.map { channel ->
        val previous = existingByStreamId[channel.streamId]
        if (previous == null) {
            channel.copy(
                lastSeenMillis = syncStamp,
                sortIndex = if (hasExisting) nextAppend++ else channel.sortIndex,
            )
        } else {
            channel.copy(
                id = previous.id,
                favourite = previous.favourite,
                // OR, not overwrite: the importer hides separator rows ('### UK ###')
                // at parse time, and that decision must stick even for rows that
                // predate the rule. User hides are preserved the same way.
                hidden = previous.hidden || channel.hidden,
                sortIndex = previous.sortIndex,
                // A user's rename must outlive the provider's fresh name on every refresh.
                customName = previous.customName,
                epgOverrideId = previous.epgOverrideId,
                matchedEpgId = previous.matchedEpgId,
                lastSeenMillis = syncStamp,
            )
        }
    }
}
