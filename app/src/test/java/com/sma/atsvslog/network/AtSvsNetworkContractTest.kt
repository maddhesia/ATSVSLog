package com.sma.atsvslog.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sma.atsvslog.network.dto.ACTION_REPORT
import com.sma.atsvslog.network.dto.ACTION_SYNC
import com.sma.atsvslog.network.dto.API_VERSION
import com.sma.atsvslog.network.dto.ApiRequest
import com.sma.atsvslog.network.dto.SaleSyncItem
import com.sma.atsvslog.network.dto.SaleSyncPayload
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Milestone 6 Fix 1 — updated for the anonymous-routing correction
 * (ADR-M6-002). Every operation now hits the same literal relative path
 * ("exec" — the base URL is the deployment's parent directory, so the
 * resolved request is server-root + "/exec", not "/") and is
 * distinguished by the `op` query parameter (GET) or the envelope's
 * `action` field (POST) instead of a distinct URL path per operation.
 */
class AtSvsNetworkContractTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun syncEnvelope_isSerializedWithoutInventingPayloadFields() = runBlocking {
        server.enqueue(successResponse())

        val client = client(apiKey = "test-secret")

        val payload = JsonObject().apply {
            addProperty("futureField", "owned-by-final-contract")
        }

        val request = ApiRequest(
            apiVersion = API_VERSION,
            requestId = "request-123",
            action = ACTION_SYNC,
            timestamp = "2026-08-20T12:00:00Z",
            payload = payload
        )

        val response = client.api.sync(request = request)

        assertTrue(response.isSuccessful)
        assertNotNull(response.body())
        assertEquals("SUCCESS", response.body()?.statusCode)
        assertEquals(API_VERSION, response.body()?.apiVersion)

        val recorded = server.takeRequest()

        assertEquals("POST", recorded.method)
        assertEquals("/exec", recorded.requestUrl?.encodedPath)
        assertEquals(
            "test-secret",
            recorded.requestUrl!!.queryParameter("apiKey")
        )

        val body = recorded.body.readUtf8()

        assertTrue(body.contains("\"apiVersion\":1"))
        assertTrue(body.contains("\"requestId\":\"request-123\""))
        assertTrue(body.contains("\"action\":\"SYNC\""))
        assertTrue(
            body.contains("\"futureField\":\"owned-by-final-contract\"")
        )
    }

    @Test
    fun apiKey_isNotSentWhenConfigDoesNotContainOne() = runBlocking {
        server.enqueue(successResponse())

        val client = client()

        val response = client.api.health()

        assertTrue(response.isSuccessful)

        val recorded = server.takeRequest()

        assertEquals("GET", recorded.method)
        assertEquals("/exec", recorded.requestUrl?.encodedPath)
        assertEquals(
            "health",
            recorded.requestUrl!!.queryParameter("op")
        )
        assertNull(
            recorded.requestUrl!!.queryParameter("apiKey")
        )
    }

    @Test
    fun allFrozenOperations_useExpectedHttpMethods_andDeserializeResponse() =
        runBlocking {

            repeat(4) {
                server.enqueue(
                    successResponse(
                        message = "operation-$it"
                    )
                )
            }

            val client = client(apiKey = "test-secret")

            val healthResponse = client.api.health()

            val syncResponse = client.api.sync(
                request = requestFor(ACTION_SYNC)
            )

            val mastersResponse = client.api.masters()

            val reportResponse = client.api.report(
                request = requestFor(ACTION_REPORT)
            )

            assertTrue(healthResponse.isSuccessful)
            assertTrue(syncResponse.isSuccessful)
            assertTrue(mastersResponse.isSuccessful)
            assertTrue(reportResponse.isSuccessful)

            assertEquals(
                "SUCCESS",
                healthResponse.body()?.statusCode
            )

            assertEquals(
                "SUCCESS",
                syncResponse.body()?.statusCode
            )

            assertEquals(
                "SUCCESS",
                mastersResponse.body()?.statusCode
            )

            assertEquals(
                "SUCCESS",
                reportResponse.body()?.statusCode
            )

            val healthRequest = server.takeRequest()

            assertEquals("GET", healthRequest.method)
            assertEquals("/exec", healthRequest.requestUrl?.encodedPath)
            assertEquals(
                "health",
                healthRequest.requestUrl!!.queryParameter("op")
            )

            val syncRequest = server.takeRequest()

            assertEquals("POST", syncRequest.method)
            assertEquals("/exec", syncRequest.requestUrl?.encodedPath)

            assertTrue(
                syncRequest.body
                    .readUtf8()
                    .contains("\"action\":\"SYNC\"")
            )

            val mastersRequest = server.takeRequest()

            assertEquals("GET", mastersRequest.method)
            assertEquals("/exec", mastersRequest.requestUrl?.encodedPath)
            assertEquals(
                "masters",
                mastersRequest.requestUrl!!.queryParameter("op")
            )

            val reportRequest = server.takeRequest()

            assertEquals("POST", reportRequest.method)
            assertEquals("/exec", reportRequest.requestUrl?.encodedPath)

            assertTrue(
                reportRequest.body
                    .readUtf8()
                    .contains("\"action\":\"REPORT\"")
            )

            assertEquals(
                "test-secret",
                healthRequest.requestUrl!!
                    .queryParameter("apiKey")
            )

            assertEquals(
                "test-secret",
                syncRequest.requestUrl!!
                    .queryParameter("apiKey")
            )

            assertEquals(
                "test-secret",
                mastersRequest.requestUrl!!
                    .queryParameter("apiKey")
            )

            assertEquals(
                "test-secret",
                reportRequest.requestUrl!!
                    .queryParameter("apiKey")
            )
        }

    @Test
    fun salePayload_serializesFrozenFieldLevelContract() = runBlocking {
        server.enqueue(successResponse())

        val client = client(apiKey = "test-secret")

        val payload = SaleSyncPayload(
            eventUuid = "event-001",
            transactionUuid = "txn-001",
            transactionDate = "2026-08-21",
            completedAt = "2026-08-21T12:00:00Z",
            items = listOf(
                SaleSyncItem(
                    itemUuid = "item-001",
                    type = "Duffle Bag",
                    brand = "Kamiliant",
                    model = "Raptor",
                    size = "M",
                    colour = "Black",
                    sellingPrice = 1599L
                )
            )
        )

        val request = ApiRequestFactory.create(
            action = ACTION_SYNC,
            payload = Gson()
                .toJsonTree(payload)
                .asJsonObject,
            requestId = "request-001",
            timestamp = "2026-08-21T12:00:01Z"
        )

        client.api.sync(request = request)

        val body = server
            .takeRequest()
            .body
            .readUtf8()

        assertTrue(
            body.contains("\"eventUuid\":\"event-001\"")
        )

        assertTrue(
            body.contains("\"eventType\":\"SALE\"")
        )

        assertTrue(
            body.contains("\"transactionUuid\":\"txn-001\"")
        )

        assertTrue(
            body.contains("\"itemUuid\":\"item-001\"")
        )

        assertTrue(
            body.contains("\"sellingPrice\":1599")
        )
    }

    @Test
    fun requestFactory_createsFrozenEnvelope_withoutDefiningBusinessPayload() {
        val payload = JsonObject().apply {
            addProperty(
                "arbitraryFutureField",
                "value-123"
            )
        }

        val request = ApiRequestFactory.create(
            action = ACTION_SYNC,
            payload = payload,
            requestId = "factory-request-001",
            timestamp = "2026-08-20T12:00:00Z"
        )

        assertEquals(
            API_VERSION,
            request.apiVersion
        )

        assertEquals(
            "factory-request-001",
            request.requestId
        )

        assertEquals(
            ACTION_SYNC,
            request.action
        )

        assertEquals(
            "2026-08-20T12:00:00Z",
            request.timestamp
        )

        assertEquals(
            "value-123",
            request.payload
                .get("arbitraryFutureField")
                .asString
        )
    }

    @Test
    fun customApiKeyParameter_isHonoured() = runBlocking {
        server.enqueue(successResponse())

        val client = AtSvsNetworkClient(
            NetworkConfig(
                baseUrl = server.url("/").toString(),
                apiKey = "custom-secret",
                apiKeyParameterName = "customKey"
            )
        )

        client.api.health()

        val recorded = server.takeRequest()

        assertEquals(
            "custom-secret",
            recorded.requestUrl!!
                .queryParameter("customKey")
        )

        assertNull(
            recorded.requestUrl!!
                .queryParameter("apiKey")
        )
    }

    @Test
    fun responseEnvelope_preservesOptionalFields_andPayload() =
        runBlocking {

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader(
                        "Content-Type",
                        "application/json"
                    )
                    .setBody(
                        """
                        {
                          "success": false,
                          "statusCode": "VALIDATION_ERROR",
                          "message": "Invalid request",
                          "serverTime": "2026-08-20T12:34:56Z",
                          "apiVersion": 1,
                          "payload": {
                            "field": "requestId",
                            "reason": "required"
                          }
                        }
                        """.trimIndent()
                    )
            )

            val client = client()

            val response = client.api.health()

            assertTrue(response.isSuccessful)
            assertNotNull(response.body())

            val body = response.body()!!

            assertFalse(body.success)
            assertEquals(
                "VALIDATION_ERROR",
                body.statusCode
            )

            assertEquals(
                "Invalid request",
                body.message
            )

            assertEquals(
                "2026-08-20T12:34:56Z",
                body.serverTime
            )

            assertEquals(
                API_VERSION,
                body.apiVersion
            )

            assertNotNull(body.payload)

            assertEquals(
                "requestId",
                body.payload
                    ?.get("field")
                    ?.asString
            )

            assertEquals(
                "required",
                body.payload
                    ?.get("reason")
                    ?.asString
            )
        }

    @Test
    fun http500_isReturnedAsUnsuccessfulResponse_withoutThrowingTransportException() =
        runBlocking {

            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setHeader(
                        "Content-Type",
                        "application/json"
                    )
                    .setBody(
                        """
                        {
                          "success": false,
                          "statusCode": "SERVER_ERROR",
                          "message": "Temporary server failure",
                          "serverTime": "2026-08-20T12:00:00Z",
                          "apiVersion": 1,
                          "payload": {}
                        }
                        """.trimIndent()
                    )
            )

            val client = client()

            val response = client.api.health()

            assertFalse(response.isSuccessful)
            assertEquals(500, response.code())
            assertNotNull(response.errorBody())
        }

    @Test
    fun http401_isReturnedAsUnsuccessfulResponse() =
        runBlocking {

            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setHeader(
                        "Content-Type",
                        "application/json"
                    )
                    .setBody(
                        """
                        {
                          "success": false,
                          "statusCode": "UNAUTHORIZED",
                          "message": "Invalid API key",
                          "serverTime": "2026-08-20T12:00:00Z",
                          "apiVersion": 1,
                          "payload": {}
                        }
                        """.trimIndent()
                    )
            )

            val client = client(
                apiKey = "wrong-key"
            )

            val response = client.api.health()

            assertFalse(response.isSuccessful)
            assertEquals(401, response.code())
            assertNotNull(response.errorBody())
        }

    @Test
    fun readTimeout_producesIOException() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader(
                    "Content-Type",
                    "application/json"
                )
                .setBody(successBody())
                .setBodyDelay(
                    1500,
                    TimeUnit.MILLISECONDS
                )
        )

        val client = AtSvsNetworkClient(
            NetworkConfig(
                baseUrl = server.url("/").toString(),
                readTimeoutSeconds = 1
            )
        )

        assertThrows(IOException::class.java) {
            runBlocking {
                client.api.health()
            }
        }
    }

    @Test
    fun malformedJson_doesNotBecomeAFakeSuccessfulResponse() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader(
                    "Content-Type",
                    "application/json"
                )
                .setBody(
                    """
                    {
                      "success": true,
                      "statusCode":
                    """.trimIndent()
                )
        )

        val client = client()

        assertThrows(Exception::class.java) {
            runBlocking {
                client.api.health()
            }
        }
    }

    @Test
    fun invalidBaseUrl_isRejectedImmediately() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NetworkConfig(
                baseUrl = "https://example.com/api"
            )
        }
    }

    private fun client(
        apiKey: String? = null
    ): AtSvsNetworkClient =
        AtSvsNetworkClient(
            NetworkConfig(
                baseUrl = server.url("/").toString(),
                apiKey = apiKey
            )
        )

    private fun requestFor(
        action: String
    ): ApiRequest<JsonObject> =
        ApiRequest(
            apiVersion = API_VERSION,
            requestId = "request-$action",
            action = action,
            timestamp = "2026-08-20T12:00:00Z",
            payload = JsonObject().apply {
                addProperty(
                    "testField",
                    "testValue"
                )
            }
        )

    private fun successResponse(
        message: String = "accepted"
    ): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader(
                "Content-Type",
                "application/json"
            )
            .setBody(
                successBody(message)
            )

    private fun successBody(
        message: String = "accepted"
    ): String =
        """
        {
          "success": true,
          "statusCode": "SUCCESS",
          "message": "$message",
          "serverTime": "2026-08-20T12:00:00Z",
          "apiVersion": 1,
          "payload": {}
        }
        """.trimIndent()
}
