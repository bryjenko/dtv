/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv.data.repo

import io.github.bryjenko.dtv.data.db.ReminderDao
import io.github.bryjenko.dtv.data.model.Reminder
import kotlinx.coroutines.flow.Flow

/** Reads and writes over the reminders table. Pure data access — the alarm wiring lives elsewhere. */
class ReminderRepository(private val dao: ReminderDao) {
    fun observeAll(): Flow<List<Reminder>> = dao.observeAll()
    fun observeStartsForChannel(channelId: Long): Flow<List<Long>> =
        dao.observeStartsForChannel(channelId)

    suspend fun byId(id: Long): Reminder? = dao.byId(id)
    suspend fun upcoming(nowMillis: Long): List<Reminder> = dao.upcoming(nowMillis)
    suspend fun forProgramme(channelId: Long, startMillis: Long): Reminder? =
        dao.forProgramme(channelId, startMillis)

    suspend fun insert(reminder: Reminder): Long = dao.insert(reminder)
    suspend fun markFired(id: Long) = dao.markFired(id)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteForProgramme(channelId: Long, startMillis: Long) =
        dao.deleteForProgramme(channelId, startMillis)

    suspend fun deleteEndedBefore(beforeMillis: Long) = dao.deleteEndedBefore(beforeMillis)
}
