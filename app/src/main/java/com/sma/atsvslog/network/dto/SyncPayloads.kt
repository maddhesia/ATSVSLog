package com.sma.atsvslog.network.dto

/**
 * Field-level SYNC payload contract.
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

data class SaleCorrectionSyncPayload(
    val eventUuid: String,
    val eventType: String = EVENT_TYPE_SALE_CORRECTION,
    val transactionUuid: String,
    val transactionDate: String,
    val completedAt: String,
    val item: SaleSyncItem
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

data class MasterSyncPayload(
    val eventUuid: String,
    val eventType: String = EVENT_TYPE_MASTER,
    val transactionUuid: String? = null,
    val itemUuid: String? = null,
    val type: String,
    val brand: String,
    val model: String,
    val size: String,
    val colour: String
)

data class MasterReclassificationSyncPayload(
    val eventUuid: String,
    val eventType: String = EVENT_TYPE_MASTER_RECLASSIFY,
    val model: String,
    val oldType: String,
    val oldBrand: String,
    val newType: String,
    val newBrand: String
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
const val EVENT_TYPE_SALE_CORRECTION = "SALE_CORRECTION"
const val EVENT_TYPE_WALK_IN = "WALK_IN"
const val EVENT_TYPE_MASTER = "MASTER"
const val EVENT_TYPE_MASTER_RECLASSIFY = "MASTER_RECLASSIFY"
const val WALK_IN_INCREMENT = "INCREMENT"
const val WALK_IN_RESET = "RESET"
