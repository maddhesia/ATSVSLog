package com.sma.atsvslog.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sma.atsvslog.database.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: SyncQueueEntity): Long

    @Query("""
        SELECT * FROM sync_queue
        WHERE status = 'Pending'
        ORDER BY createdAt ASC, queueLocalId ASC
        LIMIT 1
    """)
    suspend fun findOldestPending(): SyncQueueEntity?

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'Pending'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'Pending'")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'Failed'")
    suspend fun countFailed(): Int

    @Query("""
        SELECT * FROM sync_queue
        WHERE eventUuid = :eventUuid
        LIMIT 1
    """)
    suspend fun findByEventUuid(eventUuid: String): SyncQueueEntity?

    @Query("""
        UPDATE sync_queue
        SET status = 'Synced',
            lastAttemptAt = :attemptedAt,
            lastErrorCode = NULL
        WHERE queueLocalId = :queueLocalId
          AND status = 'Failed'
    """)
    suspend fun markFailedEventResolved(
        queueLocalId: Long,
        attemptedAt: Long
    ): Int

    @Query("""
        UPDATE sync_queue
        SET status = 'Synced',
            lastAttemptAt = :attemptedAt,
            lastErrorCode = NULL
        WHERE status = 'Pending'
          AND eventType = 'SALE'
          AND payload LIKE '%' || :transactionUuid || '%'
    """)
    suspend fun markPendingSaleEventsResolvedForTransaction(
        transactionUuid: String,
        attemptedAt: Long
    ): Int

    @Update
    suspend fun update(event: SyncQueueEntity)

    @Query("""
        UPDATE sync_queue
        SET status = 'Synced',
            lastAttemptAt = :attemptedAt,
            lastErrorCode = NULL
        WHERE queueLocalId = :queueLocalId
    """)
    suspend fun markSynced(
        queueLocalId: Long,
        attemptedAt: Long
    )

    @Query("""
        UPDATE sync_queue
        SET status = 'Pending',
            lastAttemptAt = :attemptedAt,
            attemptCount = attemptCount + 1,
            lastErrorCode = :errorCode
        WHERE queueLocalId = :queueLocalId
    """)
    suspend fun recordTemporaryFailure(
        queueLocalId: Long,
        attemptedAt: Long,
        errorCode: String
    )

    @Query("""
        UPDATE sync_queue
        SET status = 'Failed',
            lastAttemptAt = :attemptedAt,
            attemptCount = attemptCount + 1,
            lastErrorCode = :errorCode
        WHERE queueLocalId = :queueLocalId
    """)
    suspend fun markFailed(
        queueLocalId: Long,
        attemptedAt: Long,
        errorCode: String
    )
}
