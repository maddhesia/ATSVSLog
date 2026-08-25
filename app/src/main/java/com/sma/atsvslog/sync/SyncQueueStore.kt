package com.sma.atsvslog.sync

import com.sma.atsvslog.database.entity.SyncQueueEntity

/**
 * Durable queue operations required by the sync engine.
 *
 * The engine deliberately depends on this small interface rather than on
 * Room directly, which keeps the FIFO state machine independently testable.
 */
interface SyncQueueStore {
    suspend fun oldestPending(): SyncQueueEntity?

    suspend fun markSynced(
        queueLocalId: Long,
        attemptedAt: Long
    )

    suspend fun recordTemporaryFailure(
        queueLocalId: Long,
        attemptedAt: Long,
        errorCode: String
    )

    suspend fun markFailed(
        queueLocalId: Long,
        attemptedAt: Long,
        errorCode: String
    )
}
