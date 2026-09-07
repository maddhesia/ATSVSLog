package com.sma.atsvslog.network

import com.google.gson.Gson
import com.sma.atsvslog.database.entity.TransactionItemEntity
import com.sma.atsvslog.network.dto.MasterReclassificationSyncPayload
import com.sma.atsvslog.network.dto.MasterSyncPayload
import com.sma.atsvslog.network.dto.SaleSyncItem
import com.sma.atsvslog.network.dto.SaleCorrectionSyncPayload
import com.sma.atsvslog.network.dto.SaleSyncPayload
import com.sma.atsvslog.network.dto.WalkInSyncPayload
import com.sma.atsvslog.network.dto.WALK_IN_INCREMENT
import com.sma.atsvslog.network.dto.WALK_IN_RESET
import java.time.Instant
import java.util.UUID

/**
 * Builds field-level SYNC payloads. The outer ApiRequest envelope remains the
 * responsibility of the SyncEngine so every HTTP attempt gets a fresh
 * requestId while the durable eventUuid stays stable.
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

    fun createSaleCorrectionPayload(
        transactionUuid: String,
        transactionDate: String,
        completedAt: Long,
        item: TransactionItemEntity,
        eventUuid: String = UUID.randomUUID().toString()
    ): Pair<String, String> {
        val payload = SaleCorrectionSyncPayload(
            eventUuid = eventUuid,
            transactionUuid = transactionUuid,
            transactionDate = transactionDate,
            completedAt = Instant.ofEpochMilli(completedAt).toString(),
            item = SaleSyncItem(
                itemUuid = item.itemUuid,
                type = item.type,
                brand = item.brand,
                model = item.model,
                size = item.size,
                colour = item.colour,
                sellingPrice = item.sellingPrice
            )
        )
        return eventUuid to gson.toJson(payload)
    }

    fun createMasterMutationPayload(
        type: String,
        brand: String,
        model: String,
        size: String,
        colour: String,
        transactionUuid: String? = null,
        itemUuid: String? = null,
        eventUuid: String = UUID.randomUUID().toString()
    ): Pair<String, String> {
        val payload = MasterSyncPayload(
            eventUuid = eventUuid,
            type = type,
            brand = brand,
            model = model,
            size = size,
            colour = colour,
            transactionUuid = transactionUuid,
            itemUuid = itemUuid
        )

        return eventUuid to gson.toJson(payload)
    }

    fun createMasterReclassificationPayload(
        model: String,
        oldType: String,
        oldBrand: String,
        newType: String,
        newBrand: String,
        eventUuid: String = UUID.randomUUID().toString()
    ): Pair<String, String> {
        val payload = MasterReclassificationSyncPayload(
            eventUuid = eventUuid,
            model = model,
            oldType = oldType,
            oldBrand = oldBrand,
            newType = newType,
            newBrand = newBrand
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
