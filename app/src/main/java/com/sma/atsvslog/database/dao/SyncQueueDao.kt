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

    @Update
    suspend fun update(event: SyncQueueEntity)
}
