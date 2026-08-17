package com.sma.atsvslog.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["eventUuid"], unique = true),
        Index(value = ["status", "createdAt"])
    ]
)
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val queueLocalId: Long = 0,
    val eventUuid: String,
    val eventType: String,
    val payload: String,
    val status: String,
    val createdAt: Long,
    val lastAttemptAt: Long?,
    val attemptCount: Int,
    val lastErrorCode: String?
)
