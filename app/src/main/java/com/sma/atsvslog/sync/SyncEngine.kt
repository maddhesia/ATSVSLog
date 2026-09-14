package com.sma.atsvslog.sync

import com.google.gson.JsonParser
import com.sma.atsvslog.database.entity.SyncQueueEntity
import com.sma.atsvslog.diagnostics.DiagnosticsLogger
import com.sma.atsvslog.diagnostics.NoOpDiagnosticsLogger
import com.sma.atsvslog.network.ApiRequestFactory
import com.sma.atsvslog.network.dto.ACTION_SYNC
import com.sma.atsvslog.network.dto.ApiRequest
import com.sma.atsvslog.network.dto.ApiResponse
import com.google.gson.JsonObject
import retrofit2.Response
import java.io.IOException

/**
 * Implements the frozen Beta FIFO Sync Worker state machine.
 *
 * M12 adds one explicit business-conflict hook: a server-side MASTER_CONFLICT
 * is still a permanent failure in the queue state machine, but it is converted
 * into the same durable MasterConflict workflow instead of remaining a silent
 * Failed item. This preserves FIFO while giving the staff a resolution path.
 */
class SyncEngine(
    private val queue: SyncQueueStore,
    private val send: suspend (ApiRequest<JsonObject>) -> Response<ApiResponse<JsonObject>>,
    private val now: () -> Long = System::currentTimeMillis,
    private val onMasterConflict: suspend (event: SyncQueueEntity, details: MasterConflictDetails) -> Unit = { _, _ -> },
    private val isBlockedByMasterConflict: suspend (event: SyncQueueEntity) -> Boolean = { false },
    private val diagnosticsLogger: DiagnosticsLogger = NoOpDiagnosticsLogger
) {
    private var lastRequestId: String? = null
    suspend fun run(): SyncRunResult {
        while (true) {
            val event = queue.oldestPending()
                ?: return SyncRunResult.Drained

            if (isBlockedByMasterConflict(event)) {
                return SyncRunResult.BlockedByMasterConflict
            }

            when (val outcome = deliver(event)) {
                DeliveryOutcome.Success -> {
                    val attemptedAt = now()
                    queue.markSynced(
                        queueLocalId = event.queueLocalId,
                        attemptedAt = attemptedAt
                    )
                    diagnosticsLogger.log(
                        level = "INFO", event = "SYNC_SUCCESS",
                        message = "${event.eventType} accepted",
                        requestId = lastRequestId, eventUuid = event.eventUuid
                    )
                }

                is DeliveryOutcome.TemporaryFailure -> {
                    queue.recordTemporaryFailure(
                        queueLocalId = event.queueLocalId,
                        attemptedAt = now(),
                        errorCode = outcome.errorCode
                    )
                    diagnosticsLogger.log(
                        level = "WARN", event = "SYNC_TEMPORARY_FAILURE",
                        message = outcome.errorCode,
                        requestId = lastRequestId, eventUuid = event.eventUuid
                    )
                    return SyncRunResult.Retry
                }

                is DeliveryOutcome.MasterConflict -> {
                    queue.markFailed(
                        queueLocalId = event.queueLocalId,
                        attemptedAt = now(),
                        errorCode = "MASTER_CONFLICT"
                    )
                    diagnosticsLogger.log(
                        level = "ERROR", event = "MASTER_CONFLICT",
                        message = "Master ownership conflict requires operator resolution",
                        requestId = lastRequestId, eventUuid = event.eventUuid
                    )
                    onMasterConflict(event, outcome.details)
                    return SyncRunResult.StoppedAfterPermanentFailure
                }

                is DeliveryOutcome.PermanentFailure -> {
                    queue.markFailed(
                        queueLocalId = event.queueLocalId,
                        attemptedAt = now(),
                        errorCode = outcome.errorCode
                    )
                    diagnosticsLogger.log(
                        level = "ERROR", event = "SYNC_FAILED",
                        message = outcome.errorCode,
                        requestId = lastRequestId, eventUuid = event.eventUuid
                    )
                    return SyncRunResult.StoppedAfterPermanentFailure
                }
            }
        }
    }

    private suspend fun deliver(
        event: SyncQueueEntity
    ): DeliveryOutcome {
        val payload = try {
            JsonParser.parseString(event.payload).asJsonObject
        } catch (_: Exception) {
            diagnosticsLogger.log(
                level = "ERROR", event = "SYNC_FAILED",
                message = "INVALID_PAYLOAD", eventUuid = event.eventUuid
            )
            return DeliveryOutcome.PermanentFailure("INVALID_PAYLOAD")
        }

        val request = ApiRequestFactory.create(
            action = ACTION_SYNC,
            payload = payload
        )
        lastRequestId = request.requestId

        diagnosticsLogger.log(
            level = "INFO", event = "SYNC_ATTEMPT",
            message = "Sending ${event.eventType}",
            requestId = request.requestId, eventUuid = event.eventUuid
        )

        return try {
            val response = send(request)
            val body = response.body()

            if (response.isSuccessful) {
                when {
                    body?.success == true ->
                        DeliveryOutcome.Success

                    body == null ->
                        DeliveryOutcome.TemporaryFailure("EMPTY_RESPONSE")

                    body.statusCode == "MASTER_CONFLICT" ->
                        DeliveryOutcome.MasterConflict(
                            MasterConflictDetails.from(body.payload, payload)
                        )

                    body.statusCode in IDEMPOTENT_SUCCESS_CODES -> {
                        diagnosticsLogger.log(
                            level = "INFO", event = "SYNC_IDEMPOTENT_SUCCESS",
                            message = "${event.eventType} already processed by server",
                            requestId = request.requestId, eventUuid = event.eventUuid
                        )
                        DeliveryOutcome.Success
                    }

                    body.statusCode in TEMPORARY_STATUS_CODES ->
                        DeliveryOutcome.TemporaryFailure(body.statusCode)

                    else ->
                        DeliveryOutcome.PermanentFailure(body.statusCode)
                }
            } else {
                classifyHttpFailure(response.code())
            }
        } catch (_: IOException) {
            DeliveryOutcome.TemporaryFailure("IO_EXCEPTION")
        } catch (_: Exception) {
            DeliveryOutcome.TemporaryFailure("NETWORK_EXCEPTION")
        }
    }

    private fun classifyHttpFailure(
        httpCode: Int
    ): DeliveryOutcome =
        when {
            httpCode == 408 ||
                httpCode == 425 ||
                httpCode == 429 ||
                httpCode in 500..599 ->
                DeliveryOutcome.TemporaryFailure("HTTP_$httpCode")

            else ->
                DeliveryOutcome.PermanentFailure("HTTP_$httpCode")
        }
}

data class MasterConflictDetails(
    val itemUuid: String,
    val model: String,
    val requestedType: String,
    val requestedBrand: String,
    val canonicalType: String,
    val canonicalBrand: String,
    val transactionUuid: String
) {
    companion object {
        fun from(
            responsePayload: JsonObject?,
            requestPayload: JsonObject
        ): MasterConflictDetails {
            fun value(name: String): String =
                responsePayload?.get(name)?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()

            return MasterConflictDetails(
                itemUuid = value("itemUuid").ifBlank {
                    requestPayload.get("itemUuid")?.asString.orEmpty()
                },
                model = value("model").ifBlank { requestPayload.get("model")?.asString.orEmpty() },
                requestedType = value("requestedType").ifBlank { requestPayload.get("type")?.asString.orEmpty() },
                requestedBrand = value("requestedBrand").ifBlank { requestPayload.get("brand")?.asString.orEmpty() },
                canonicalType = value("canonicalType"),
                canonicalBrand = value("canonicalBrand"),
                transactionUuid = requestPayload.get("transactionUuid")?.asString.orEmpty()
            )
        }
    }
}

sealed interface SyncRunResult {
    data object Drained : SyncRunResult
    data object Retry : SyncRunResult
    data object StoppedAfterPermanentFailure : SyncRunResult
    data object BlockedByMasterConflict : SyncRunResult
}

private sealed interface DeliveryOutcome {
    data object Success : DeliveryOutcome

    data class TemporaryFailure(
        val errorCode: String
    ) : DeliveryOutcome

    data class MasterConflict(
        val details: MasterConflictDetails
    ) : DeliveryOutcome

    data class PermanentFailure(
        val errorCode: String
    ) : DeliveryOutcome
}

private val IDEMPOTENT_SUCCESS_CODES = setOf(
    "DUPLICATE",
    "IDEMPOTENT_SUCCESS",
    "ALREADY_PROCESSED"
)

private val TEMPORARY_STATUS_CODES = setOf(
    "TEMPORARY_ERROR",
    "SERVER_ERROR",
    "SERVICE_UNAVAILABLE",
    "RATE_LIMITED",
    "TIMEOUT"
)
