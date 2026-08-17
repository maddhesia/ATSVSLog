package com.sma.atsvslog.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sma.atsvslog.database.entity.MasterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MasterDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(master: MasterEntity): Long

    @Update
    suspend fun update(master: MasterEntity)

    @Query("SELECT DISTINCT type FROM masters ORDER BY type COLLATE NOCASE")
    fun observeTypes(): Flow<List<String>>

    @Query("SELECT DISTINCT model FROM masters WHERE brand = :brand ORDER BY model COLLATE NOCASE")
    fun observeModels(brand: String): Flow<List<String>>

    @Query("""
        SELECT DISTINCT size FROM masters
        WHERE brand = :brand AND model = :model
        ORDER BY size COLLATE NOCASE
    """)
    fun observeSizes(brand: String, model: String): Flow<List<String>>

    @Query("SELECT DISTINCT colour FROM masters ORDER BY colour COLLATE NOCASE")
    fun observeColours(): Flow<List<String>>

    @Query("""
        SELECT * FROM masters
        WHERE type = :type
          AND brand = :brand
          AND model = :model
          AND size = :size
          AND colour = :colour
        LIMIT 1
    """)
    suspend fun findCombination(
        type: String,
        brand: String,
        model: String,
        size: String,
        colour: String
    ): MasterEntity?
}
