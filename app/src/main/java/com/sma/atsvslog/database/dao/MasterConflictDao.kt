package com.sma.atsvslog.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sma.atsvslog.database.entity.MasterConflictEntity
import com.sma.atsvslog.database.entity.MASTER_CONFLICT_PENDING
import kotlinx.coroutines.flow.Flow

@Dao
interface MasterConflictDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(conflict: MasterConflictEntity): Long

    @Query("""
        SELECT * FROM master_conflicts
        WHERE status = '$MASTER_CONFLICT_PENDING'
        ORDER BY createdAt ASC, localId ASC
        LIMIT 1
    """)
    fun observeOldestPending(): Flow<MasterConflictEntity?>

    @Query("""
        SELECT * FROM master_conflicts
        WHERE status = '$MASTER_CONFLICT_PENDING'
          AND transactionUuid = :transactionUuid
        ORDER BY createdAt ASC, localId ASC
    """)
    suspend fun findPendingForTransaction(transactionUuid: String): List<MasterConflictEntity>


    @Query("""
        SELECT * FROM master_conflicts
        WHERE localId = :localId
          AND status = '$MASTER_CONFLICT_PENDING'
        LIMIT 1
    """)
    suspend fun findPendingById(localId: Long): MasterConflictEntity?

    @Query("""
        SELECT * FROM master_conflicts
        WHERE status = '$MASTER_CONFLICT_PENDING'
          AND transactionUuid = :transactionUuid
          AND model = :model COLLATE NOCASE
          AND requestedType = :requestedType COLLATE NOCASE
          AND requestedBrand = :requestedBrand COLLATE NOCASE
        LIMIT 1
    """)
    suspend fun findPending(
        transactionUuid: String,
        model: String,
        requestedType: String,
        requestedBrand: String
    ): MasterConflictEntity?

    @Query("""
        SELECT COUNT(*) FROM master_conflicts
        WHERE status = '$MASTER_CONFLICT_PENDING'
          AND transactionUuid = :transactionUuid
    """)
    suspend fun countPendingForTransaction(transactionUuid: String): Int

    @Query("""
        UPDATE master_conflicts
        SET notifiedAt = :notifiedAt
        WHERE localId = :localId
          AND status = '$MASTER_CONFLICT_PENDING'
    """)
    suspend fun markNotified(localId: Long, notifiedAt: Long): Int

    @Query("""
        UPDATE master_conflicts
        SET status = 'Resolved', resolvedAt = :resolvedAt
        WHERE localId = :localId
          AND status = '$MASTER_CONFLICT_PENDING'
    """)
    suspend fun markResolved(localId: Long, resolvedAt: Long): Int

    @Query("""
        UPDATE master_conflicts
        SET notifiedAt = NULL
        WHERE localId = :localId
          AND status = '$MASTER_CONFLICT_PENDING'
    """)
    suspend fun clearNotification(localId: Long): Int
}
