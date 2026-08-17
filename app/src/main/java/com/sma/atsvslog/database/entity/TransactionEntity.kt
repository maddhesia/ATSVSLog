package com.sma.atsvslog.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["transactionUuid"], unique = true),
        Index(value = ["transactionDate"])
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    val transactionUuid: String,
    val transactionDate: String,
    val createdAt: Long,
    val completedAt: Long?
)
