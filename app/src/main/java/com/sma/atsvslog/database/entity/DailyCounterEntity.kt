package com.sma.atsvslog.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_counters")
data class DailyCounterEntity(
    @PrimaryKey
    val date: String,
    val walkIns: Int = 0,
    val conversions: Int = 0,
    val updatedAt: Long
)
