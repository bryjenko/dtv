/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv.data

import io.github.bryjenko.dtv.data.db.PlaybackPositionDao
import io.github.bryjenko.dtv.data.model.PlaybackPosition
import io.github.bryjenko.dtv.data.model.SaveAction
import io.github.bryjenko.dtv.data.model.WatchProgress
import io.github.bryjenko.dtv.data.model.parseMediaKey

/**
 * Writes a playback sample into [playback_positions].
 *
 * Each row is stamped with the playlist it came from ([sourceId]) as well as the profile, so two
 * providers never share a resume point. The media key stays `movie:<id>` / `ep:<id>`: those ids
 * are the catalogue row ids, already unique per provider item, which is what resume and sync
 * already look up.
 */
object PlaybackProgressStore {
    suspend fun save(
        dao: PlaybackPositionDao,
        profileId: Long,
        sourceId: Long,
        mediaKey: String,
        positionMillis: Long,
        durationMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val kind = parseMediaKey(mediaKey)?.kind ?: mediaKey.substringBefore(':', "")
        when (WatchProgress.saveAction(kind, positionMillis, durationMillis)) {
            SaveAction.IGNORE -> Unit
            SaveAction.DELETE -> dao.delete(profileId, mediaKey)
            SaveAction.UPSERT -> dao.upsert(
                PlaybackPosition(
                    profileId = profileId,
                    mediaKey = mediaKey,
                    positionMillis = positionMillis,
                    durationMillis = durationMillis,
                    updatedAtMillis = nowMillis,
                    sourceId = sourceId,
                ),
            )
        }
    }

    /** Removes every listed key for one profile. Empty input is a no-op (Room rejects an empty IN). */
    suspend fun deleteKeys(dao: PlaybackPositionDao, profileId: Long, keys: Collection<String>) {
        keys.distinct().filter { it.isNotEmpty() }.chunked(400).forEach { chunk ->
            dao.deleteKeys(profileId, chunk)
        }
    }
}
