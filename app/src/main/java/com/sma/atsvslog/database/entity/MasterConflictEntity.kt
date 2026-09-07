package com.sma.atsvslog.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable record of a Model Type/Brand conflict that must be resolved before
 * the affected sale is allowed to reach the cloud.
 *
 * The conflict record is durable. EDIT SALE corrects the affected historical
 * TransactionItem in place without changing Master ownership; REASSIGN MODEL
 * changes only current Master ownership and creates a durable
 * MASTER_RECLASSIFY correction event for the cloud side.
 */
@Entity(
    tableName = "master_conflicts",
    indices = [
        Index(
            value = ["transactionUuid", "model", "requestedType", "requestedBrand", "status"],
            unique = true
        ),
        Index(value = ["status", "createdAt"])
    ]
)
data class MasterConflictEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    val conflictUuid: String,
    val transactionUuid: String,
    val itemUuid: String? = null,
    val model: String,
    val requestedType: String,
    val requestedBrand: String,
    val canonicalType: String,
    val canonicalBrand: String,
    val createdAt: Long,
    val blockedQueueLocalId: Long? = null,
    val notifiedAt: Long? = null,
    val resolvedAt: Long? = null,
    val status: String = MASTER_CONFLICT_PENDING
)

const val MASTER_CONFLICT_PENDING = "Pending"
const val MASTER_CONFLICT_RESOLVED = "Resolved"
