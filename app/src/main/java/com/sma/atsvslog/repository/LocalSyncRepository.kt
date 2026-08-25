package com.sma.atsvslog.repository

import com.sma.atsvslog.database.ATSVSLogDatabase
import com.sma.atsvslog.database.entity.SyncQueueEntity
import com.sma.atsvslog.sync.SyncQueueStore
import kotlinx.coroutines.flow.Flow

class LocalSyncRepository(
    private val database: ATSVSLogDatabase
) : SyncQueueStore {
    private val dao = database.syncQueueDao()

    suspend fun enqueue(event: SyncQueueEntity): Long = dao.insert(event)

    override suspend fun oldestPending(): SyncQueueEntity? = dao.findOldestPending()

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    suspend fun update(event: SyncQueueEntity) = dao.update(event)

    override suspend fun markSynced(
        queueLocalId: Long,
        attemptedAt: Long
    ) = dao.markSynced(queueLocalId, attemptedAt)

    override suspend fun recordTemporaryFailure(
        queueLocalId: Long,
        attemptedAt: Long,
        errorCode: String
    ) = dao.recordTemporaryFailure(
        queueLocalId = queueLocalId,
        attemptedAt = attemptedAt,
        errorCode = errorCode
    )

    override suspend fun markFailed(
        queueLocalId: Long,
        attemptedAt: Long,
        errorCode: String
    ) = dao.markFailed(
        queueLocalId = queueLocalId,
        attemptedAt = attemptedAt,
        errorCode = errorCode
    )
}
