package com.sma.atsvslog.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transaction_items",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["transactionUuid"],
            childColumns = ["transactionUuid"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["itemUuid"], unique = true),
        Index(value = ["transactionUuid"])
    ]
)
data class TransactionItemEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    val itemUuid: String,
    val transactionUuid: String,
    val type: String,
    val brand: String,
    val model: String,
    val size: String,
    val colour: String,
    val sellingPrice: Long
)
