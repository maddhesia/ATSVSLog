package com.sma.atsvslog.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sma.atsvslog.database.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("""
        SELECT * FROM transactions
        WHERE transactionDate = :date
        ORDER BY createdAt ASC
    """)
    fun observeByDate(date: String): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions
        WHERE transactionUuid = :transactionUuid
        LIMIT 1
    """)
    suspend fun findByUuid(transactionUuid: String): TransactionEntity?
}
