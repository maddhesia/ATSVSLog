package com.sma.atsvslog.sync

import com.google.gson.JsonObject
import com.sma.atsvslog.database.entity.SyncQueueEntity
import com.sma.atsvslog.network.dto.ApiResponse
import com.sma.atsvslog.network.dto.ApiRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class SyncEngineTest {

    @Test
    fun success_drainsFifoQueue_andMarksEachEventSynced() = kotlinx.coroutines.runBlocking {
        val first = event(1, "event-1", """{"eventUuid":"event-1","eventType":"WALK_IN"}""")
        val second = event(2, "event-2", """{"eventUuid":"event-2","eventType":"SALE"}""")
        val store = FakeQueueStore(first, second)
        val sentRequestIds = mutableListOf<String>()

        val result = SyncEngine(
            queue = store,
            send = { request ->
                sentRequestIds += request.requestId
                Response.success(successResponse())
            },
            now = { 1000L }
        ).run()

        assertEquals(SyncRunResult.Drained, result)
        assertEquals(listOf("event-1", "event-2"), store.syncedEventUuids)
        assertTrue(sentRequestIds.all { it.isNotBlank() })
        assertEquals(2, sentRequestIds.distinct().size)
    }

    @Test
    fun idempotentDuplicate_isTreatedAsSynced() = kotlinx.coroutines.runBlocking {
        val item = event(1, "event-1", """{"eventUuid":"event-1","eventType":"SALE"}""")
        val store = FakeQueueStore(item)

        val result = SyncEngine(
            queue = store,
            send = {
                Response.success(
                    ApiResponse(
                        success = false,
                        statusCode = "DUPLICATE",
                        message = "Already processed",
                        serverTime = "2026-08-25T00:00:00Z",
                        apiVersion = 1,
                        payload = JsonObject()
                    )
                )
            },
            now = { 2000L }
        ).run()

        assertEquals(SyncRunResult.Drained, result)
        assertEquals(listOf("event-1"), store.syncedEventUuids)
    }

    @Test
    fun temporaryFailure_keepsPending_incrementsAttempt_andStopsFifo() =
        kotlinx.coroutines.runBlocking {
            val first = event(1, "event-1", """{"eventUuid":"event-1","eventType":"SALE"}""")
            val second = event(2, "event-2", """{"eventUuid":"event-2","eventType":"WALK_IN"}""")
            val store = FakeQueueStore(first, second)

            val result = SyncEngine(
                queue = store,
                send = {
                    Response.error(
                        500,
                        """{"success":false,"statusCode":"SERVER_ERROR"}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                },
                now = { 3000L }
            ).run()

            assertEquals(SyncRunResult.Retry, result)
            assertEquals(1, store.temporaryFailures.size)
            assertEquals("event-1", store.temporaryFailures.single().first)
            assertEquals("HTTP_500", store.temporaryFailures.single().second)
            assertTrue(store.syncedEventUuids.isEmpty())
            assertEquals(1, store.pending.first().attemptCount)
        }

    @Test
    fun permanentFailure_marksFailed_and_doesNotProcessLaterEvents() =
        kotlinx.coroutines.runBlocking {
            val first = event(1, "event-1", """{"eventUuid":"event-1","eventType":"SALE"}""")
            val second = event(2, "event-2", """{"eventUuid":"event-2","eventType":"WALK_IN"}""")
            val store = FakeQueueStore(first, second)

            val result = SyncEngine(
                queue = store,
                send = {
                    Response.error(
                        400,
                        """{"success":false,"statusCode":"VALIDATION_ERROR"}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                },
                now = { 4000L }
            ).run()

            assertEquals(SyncRunResult.StoppedAfterPermanentFailure, result)
            assertEquals(listOf("event-1"), store.failedEventUuids)
            assertTrue(store.syncedEventUuids.isEmpty())
            assertEquals("Failed", store.pending.first().status)
            assertEquals("HTTP_400", store.pending.first().lastErrorCode)
            assertEquals("Pending", store.pending[1].status)
        }

    @Test
    fun malformedQueuePayload_isPermanentFailure() =
        kotlinx.coroutines.runBlocking {
            val item = event(1, "event-1", """{"eventUuid":""")
            val store = FakeQueueStore(item)

            val result = SyncEngine(
                queue = store,
                send = { error("send must not be called") },
                now = { 5000L }
            ).run()

            assertEquals(SyncRunResult.StoppedAfterPermanentFailure, result)
            assertEquals(listOf("event-1"), store.failedEventUuids)
            assertEquals("INVALID_PAYLOAD", store.pending.first().lastErrorCode)
        }

    private fun successResponse(): ApiResponse<JsonObject> =
        ApiResponse(
            success = true,
            statusCode = "SUCCESS",
            message = "Accepted",
            serverTime = "2026-08-25T00:00:00Z",
            apiVersion = 1,
            payload = JsonObject()
        )

    private fun event(
        id: Long,
        uuid: String,
        payload: String
    ) = SyncQueueEntity(
        queueLocalId = id,
        eventUuid = uuid,
        eventType = "TEST",
        payload = payload,
        status = "Pending",
        createdAt = id,
        lastAttemptAt = null,
        attemptCount = 0,
        lastErrorCode = null
    )
}

private class FakeQueueStore(
    vararg initial: SyncQueueEntity
) : SyncQueueStore {

    val pending = initial.toMutableList()
    val syncedEventUuids = mutableListOf<String>()
    val failedEventUuids = mutableListOf<String>()
    val temporaryFailures = mutableListOf<Pair<String, String>>()

    override suspend fun oldestPending(): SyncQueueEntity? =
        pending
            .filter { it.status == "Pending" }
            .minWithOrNull(
                compareBy<SyncQueueEntity> { it.createdAt }
                    .thenBy { it.queueLocalId }
            )

    override suspend fun markSynced(
        queueLocalId: Long,
        attemptedAt: Long
    ) {
        val index = pending.indexOfFirst { it.queueLocalId == queueLocalId }
        val current = pending[index]
        pending[index] = current.copy(
            status = "Synced",
            lastAttemptAt = attemptedAt,
            lastErrorCode = null
        )
        syncedEventUuids += current.eventUuid
    }

    override suspend fun recordTemporaryFailure(
        queueLocalId: Long,
        attemptedAt: Long,
        errorCode: String
    ) {
        val index = pending.indexOfFirst { it.queueLocalId == queueLocalId }
        val current = pending[index]
        pending[index] = current.copy(
            status = "Pending",
            lastAttemptAt = attemptedAt,
            attemptCount = current.attemptCount + 1,
            lastErrorCode = errorCode
        )
        temporaryFailures += current.eventUuid to errorCode
    }

    override suspend fun markFailed(
        queueLocalId: Long,
        attemptedAt: Long,
        errorCode: String
    ) {
        val index = pending.indexOfFirst { it.queueLocalId == queueLocalId }
        val current = pending[index]
        pending[index] = current.copy(
            status = "Failed",
            lastAttemptAt = attemptedAt,
            attemptCount = current.attemptCount + 1,
            lastErrorCode = errorCode
        )
        failedEventUuids += current.eventUuid
    }
}
