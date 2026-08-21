package com.sma.atsvslog.network

import com.google.gson.JsonObject
import com.sma.atsvslog.network.dto.API_VERSION
import com.sma.atsvslog.network.dto.ApiRequest
import java.time.Instant
import java.util.UUID

/**
 * Creates the frozen outer API envelope without defining the still-pending
 * field-level SALE payload.
 */
object ApiRequestFactory {

    fun create(
        action: String,
        payload: JsonObject,
        requestId: String = UUID.randomUUID().toString(),
        timestamp: String = Instant.now().toString()
    ): ApiRequest<JsonObject> =
        ApiRequest(
            apiVersion = API_VERSION,
            requestId = requestId,
            action = action,
            timestamp = timestamp,
            payload = payload
        )
}
