package com.sma.atsvslog.network

import com.google.gson.Gson
import com.sma.atsvslog.database.entity.TransactionItemEntity
import com.sma.atsvslog.network.dto.SaleSyncItem
import com.sma.atsvslog.network.dto.SaleSyncPayload
import com.sma.atsvslog.network.dto.WalkInSyncPayload
import com.sma.atsvslog.network.dto.WALK_IN_INCREMENT
import com.sma.atsvslog.network.dto.WALK_IN_RESET
import java.time.Instant
import java.util.UUID

/**
 * Builds the already-frozen Milestone 6 field-level SYNC payloads.
 *
 * This class does NOT create the outer ApiRequest envelope. That remains
 * the responsibility of the future Sync Worker, where requestId must be
 * generated separately for every HTTP attempt.
 */
object SyncPayloadFactory {

    private val gson = Gson()

    fun createSalePayload(
        transactionUuid: String,
        transactionDate: String,
        completedAt: Long,
        items: List<TransactionItemEntity>,
        eventUuid: String = UUID.randomUUID().toString()
    ): Pair<String, String> {
        require(items.isNotEmpty()) {
            "SALE sync event requires at least one item."
        }

        val payload = SaleSyncPayload(
            eventUuid = eventUuid,
            transactionUuid = transactionUuid,
            transactionDate = transactionDate,
            completedAt = Instant.ofEpochMilli(completedAt).toString(),
            items = items.map { item ->
                SaleSyncItem(
                    itemUuid = item.itemUuid,
                    type = item.type,
                    brand = item.brand,
                    model = item.model,
                    size = item.size,
                    colour = item.colour,
                    sellingPrice = item.sellingPrice
                )
            }
        )

        return eventUuid to gson.toJson(payload)
    }

    fun createWalkInIncrementPayload(
        businessDate: String,
        resultingWalkIns: Int,
        eventUuid: String = UUID.randomUUID().toString()
    ): Pair<String, String> {
        val payload = WalkInSyncPayload(
            eventUuid = eventUuid,
            businessDate = businessDate,
            operation = WALK_IN_INCREMENT,
            delta = 1,
            resultingWalkIns = resultingWalkIns
        )

        return eventUuid to gson.toJson(payload)
    }

    fun createWalkInDecrementPayload(
        businessDate: String,
        resultingWalkIns: Int,
        eventUuid: String = UUID.randomUUID().toString()
    ): Pair<String, String> {
        val payload = WalkInSyncPayload(
            eventUuid = eventUuid,
            businessDate = businessDate,
            operation = WALK_IN_INCREMENT,
            delta = -1,
            resultingWalkIns = resultingWalkIns
        )

        return eventUuid to gson.toJson(payload)
    }

    fun createWalkInResetPayload(
        businessDate: String,
        eventUuid: String = UUID.randomUUID().toString()
    ): Pair<String, String> {
        val payload = WalkInSyncPayload(
            eventUuid = eventUuid,
            businessDate = businessDate,
            operation = WALK_IN_RESET,
            delta = 0,
            resultingWalkIns = 0
        )

        return eventUuid to gson.toJson(payload)
    }
}
