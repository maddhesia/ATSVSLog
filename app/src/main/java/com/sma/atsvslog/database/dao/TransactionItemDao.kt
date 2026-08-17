package com.sma.atsvslog.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sma.atsvslog.database.entity.TransactionItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionItemDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: TransactionItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<TransactionItemEntity>)

    @Query("""
        SELECT * FROM transaction_items
        WHERE transactionUuid = :transactionUuid
        ORDER BY localId ASC
    """)
    fun observeForTransaction(transactionUuid: String): Flow<List<TransactionItemEntity>>

    @Query("""
        SELECT * FROM transaction_items
        WHERE transactionUuid = :transactionUuid
        ORDER BY localId ASC
    """)
    suspend fun findForTransaction(transactionUuid: String): List<TransactionItemEntity>

    @Query("""
        SELECT * FROM transaction_items
        WHERE model = :model
          AND size = :size
          AND colour = :colour
        ORDER BY localId DESC
        LIMIT 1
    """)
    suspend fun findLatestForPrice(
        model: String,
        size: String,
        colour: String
    ): TransactionItemEntity?
}
