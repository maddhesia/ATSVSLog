package com.sma.atsvslog.repository

import com.sma.atsvslog.database.ATSVSLogDatabase
import com.sma.atsvslog.database.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

class LocalSyncRepository(
    private val database: ATSVSLogDatabase
) {
    private val dao = database.syncQueueDao()

    suspend fun enqueue(event: SyncQueueEntity): Long = dao.insert(event)

    suspend fun oldestPending(): SyncQueueEntity? = dao.findOldestPending()

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    suspend fun update(event: SyncQueueEntity) = dao.update(event)
}
