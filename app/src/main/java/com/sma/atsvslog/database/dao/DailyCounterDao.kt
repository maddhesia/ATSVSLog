package com.sma.atsvslog.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sma.atsvslog.database.entity.DailyCounterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyCounterDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(counter: DailyCounterEntity): Long

    @Query("SELECT * FROM daily_counters WHERE date = :date LIMIT 1")
    fun observe(date: String): Flow<DailyCounterEntity?>

    @Query("SELECT * FROM daily_counters WHERE date = :date LIMIT 1")
    suspend fun find(date: String): DailyCounterEntity?

    @Query("""
        UPDATE daily_counters
        SET walkIns = walkIns + :delta,
            updatedAt = :updatedAt
        WHERE date = :date
    """)
    suspend fun changeWalkIns(date: String, delta: Int, updatedAt: Long): Int

    @Query("""
        UPDATE daily_counters
        SET conversions = conversions + 1,
            updatedAt = :updatedAt
        WHERE date = :date
    """)
    suspend fun incrementConversions(date: String, updatedAt: Long): Int

    @Query("""
        UPDATE daily_counters
        SET walkIns = 0,
            updatedAt = :updatedAt
        WHERE date = :date
    """)
    suspend fun resetWalkIns(date: String, updatedAt: Long): Int
}
