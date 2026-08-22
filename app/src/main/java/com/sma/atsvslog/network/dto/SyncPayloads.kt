package com.sma.atsvslog.network.dto

/**
 * Field-level SYNC payload contract frozen in Milestone 6.
 *
 * eventUuid is the durable SyncQueue event identity and remains stable across
 * retries. requestId in the outer envelope is unique per HTTP attempt.
 */
data class SaleSyncPayload(
    val eventUuid: String,
    val eventType: String = EVENT_TYPE_SALE,
    val transactionUuid: String,
    val transactionDate: String,
    val completedAt: String,
    val items: List<SaleSyncItem>
)

data class SaleSyncItem(
    val itemUuid: String,
    val type: String,
    val brand: String,
    val model: String,
    val size: String,
    val colour: String,
    val sellingPrice: Long
)

data class WalkInSyncPayload(
    val eventUuid: String,
    val eventType: String = EVENT_TYPE_WALK_IN,
    val businessDate: String,
    val operation: String,
    val delta: Int,
    val resultingWalkIns: Int? = null
)

const val EVENT_TYPE_SALE = "SALE"
const val EVENT_TYPE_WALK_IN = "WALK_IN"
const val WALK_IN_INCREMENT = "INCREMENT"
const val WALK_IN_RESET = "RESET"
