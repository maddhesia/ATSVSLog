package com.sma.atsvslog.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "masters",
    indices = [
        Index(
            value = ["type", "brand", "model", "size", "colour"],
            unique = true
        ),
        Index(value = ["model"]),
        Index(value = ["colour"]),
        Index(value = ["size"])
    ]
)
data class MasterEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    val type: String,
    val brand: String,
    val model: String,
    val size: String,
    val colour: String,
    val lastSellingPrice: Long?,
    val lastSoldAt: Long?
)
