package com.sma.atsvslog.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sma.atsvslog.network.AtSvsApi
import com.sma.atsvslog.network.dto.ApiRequest
import com.sma.atsvslog.network.dto.ApiResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class CloudMasterRepositoryTest {

    @Test
    fun existingModelUnderDifferentOwner_returnsConflict() = runBlocking {
        val api = FakeApi(
            JsonArray().apply {
                add(master("Trolley Bag", "Kamiliant", "Diamo", "55", "Black"))
            }
        )

        val result = CloudMasterRepository(api).checkModelOwnership(
            model = "Diamo",
            requestedType = "Trolley Bag",
            requestedBrand = "American Tourister"
        )

        assertTrue(result is CloudModelOwnershipResult.Conflict)
        result as CloudModelOwnershipResult.Conflict
        assertEquals("Trolley Bag", result.canonicalType)
        assertEquals("Kamiliant", result.canonicalBrand)
    }

    @Test
    fun absentModel_returnsNoConflict() = runBlocking {
        val result = CloudMasterRepository(FakeApi(JsonArray()))
            .checkModelOwnership("Diamo", "Trolley Bag", "American Tourister")

        assertEquals(CloudModelOwnershipResult.NoConflict, result)
    }

    private fun master(
        type: String,
        brand: String,
        model: String,
        size: String,
        colour: String
    ) = JsonObject().apply {
        addProperty("type", type)
        addProperty("brand", brand)
        addProperty("model", model)
        addProperty("size", size)
        addProperty("colour", colour)
    }

    private class FakeApi(
        private val masters: JsonArray
    ) : AtSvsApi {
        override suspend fun health(op: String): Response<ApiResponse<JsonObject>> =
            throw UnsupportedOperationException()

        override suspend fun sync(
            request: ApiRequest<JsonObject>
        ): Response<ApiResponse<JsonObject>> =
            throw UnsupportedOperationException()

        override suspend fun masters(
            op: String
        ): Response<ApiResponse<JsonObject>> =
            Response.success(
                ApiResponse(
                    success = true,
                    statusCode = "SUCCESS",
                    message = "Masters returned",
                    serverTime = "2026-08-31T00:00:00Z",
                    apiVersion = 1,
                    payload = JsonObject().apply { add("masters", masters) }
                )
            )

        override suspend fun report(
            request: ApiRequest<JsonObject>
        ): Response<ApiResponse<JsonObject>> =
            throw UnsupportedOperationException()
    }
}
