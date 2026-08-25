package com.sma.atsvslog.sync

import com.google.gson.JsonParser
import com.sma.atsvslog.database.entity.SyncQueueEntity
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
 * One queue item becomes exactly one SYNC HTTP request.
 * The durable eventUuid remains inside the payload; requestId is generated
 * separately for every HTTP attempt.
 */
class SyncEngine(
    private val queue: SyncQueueStore,
    private val send: suspend (ApiRequest<JsonObject>) -> Response<ApiResponse<JsonObject>>,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun run(): SyncRunResult {
        while (true) {
            val event = queue.oldestPending()
                ?: return SyncRunResult.Drained

            when (val outcome = deliver(event)) {
                DeliveryOutcome.Success -> {
                    queue.markSynced(
                        queueLocalId = event.queueLocalId,
                        attemptedAt = now()
                    )
                }

                is DeliveryOutcome.TemporaryFailure -> {
                    queue.recordTemporaryFailure(
                        queueLocalId = event.queueLocalId,
                        attemptedAt = now(),
                        errorCode = outcome.errorCode
                    )
                    return SyncRunResult.Retry
                }

                is DeliveryOutcome.PermanentFailure -> {
                    queue.markFailed(
                        queueLocalId = event.queueLocalId,
                        attemptedAt = now(),
                        errorCode = outcome.errorCode
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
            return DeliveryOutcome.PermanentFailure("INVALID_PAYLOAD")
        }

        val request = ApiRequestFactory.create(
            action = ACTION_SYNC,
            payload = payload
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

                    body.statusCode in IDEMPOTENT_SUCCESS_CODES ->
                        DeliveryOutcome.Success

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
            // A transport/converter failure is not evidence that the business
            // event is invalid. Leave it Pending and retry later.
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

sealed interface SyncRunResult {
    data object Drained : SyncRunResult
    data object Retry : SyncRunResult
    data object StoppedAfterPermanentFailure : SyncRunResult
}

private sealed interface DeliveryOutcome {
    data object Success : DeliveryOutcome

    data class TemporaryFailure(
        val errorCode: String
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
