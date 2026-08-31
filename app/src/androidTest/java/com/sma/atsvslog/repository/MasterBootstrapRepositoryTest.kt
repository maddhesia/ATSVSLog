package com.sma.atsvslog.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sma.atsvslog.database.ATSVSLogDatabase
import com.sma.atsvslog.database.entity.MasterEntity
import com.sma.atsvslog.network.AtSvsApi
import com.sma.atsvslog.network.dto.ApiRequest
import com.sma.atsvslog.network.dto.ApiResponse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class MasterBootstrapRepositoryTest {

    private val database = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        ATSVSLogDatabase::class.java
    ).allowMainThreadQueries().build()

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun emptyCache_bootstrapsAndRepeatedBootstrapIsIdempotent() = runBlocking {
        val api = FakeMastersApi(
            masters(
                master("Duffle Bag", "Kamiliant", "Raptor", "55", "Black"),
                master("Trolley Bag", "American Tourister", "Aerostep", "55", "Navy")
            )
        )
        val repository = MasterBootstrapRepository(database, api)

        val first = repository.bootstrap()
        val second = repository.bootstrap()

        assertEquals(2, first.fetched)
        assertEquals(2, first.inserted)
        assertEquals(0, first.alreadyPresent)
        assertEquals(0, first.preservedLocalConflicts)

        assertEquals(2, second.fetched)
        assertEquals(0, second.inserted)
        assertEquals(2, second.alreadyPresent)
        assertEquals(0, second.preservedLocalConflicts)
        assertEquals(2, database.masterDao().getAllMasters().size)
    }

    @Test
    fun bootstrap_mergesNewVariantButPreservesConflictingLocalModel() = runBlocking {
        database.masterDao().insert(
            MasterEntity(
                type = "Local Type",
                brand = "Local Brand",
                model = "LocalModel",
                size = "55",
                colour = "Black",
                lastSellingPrice = 1000,
                lastSoldAt = 1L
            )
        )
        database.masterDao().insert(
            MasterEntity(
                type = "Duffle Bag",
                brand = "Kamiliant",
                model = "Raptor",
                size = "55",
                colour = "Black",
                lastSellingPrice = 1599,
                lastSoldAt = 2L
            )
        )

        val api = FakeMastersApi(
            masters(
                master("Cloud Type", "Cloud Brand", "LocalModel", "60", "Blue"),
                master("Duffle Bag", "Kamiliant", "Raptor", "55", "Black"),
                master("Duffle Bag", "Kamiliant", "Raptor", "68", "Navy")
            )
        )

        val result = MasterBootstrapRepository(database, api).bootstrap()
        val local = database.masterDao().getAllMasters()

        assertEquals(3, result.fetched)
        assertEquals(1, result.inserted)
        assertEquals(1, result.alreadyPresent)
        assertEquals(1, result.preservedLocalConflicts)
        assertEquals(3, local.size)
        assertTrue(local.any { it.model == "Raptor" && it.size == "68" })
        assertTrue(local.any { it.model == "LocalModel" && it.size == "55" })
        assertTrue(local.none { it.model == "LocalModel" && it.size == "60" })
    }

    private fun master(
        type: String,
        brand: String,
        model: String,
        size: String,
        colour: String
    ): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("brand", brand)
        addProperty("model", model)
        addProperty("size", size)
        addProperty("colour", colour)
    }

    private fun masters(vararg values: JsonObject): JsonArray =
        JsonArray().apply { values.forEach(::add) }

    private class FakeMastersApi(
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
                    serverTime = "2026-08-29T00:00:00Z",
                    apiVersion = 1,
                    payload = JsonObject().apply {
                        add("masters", masters)
                    }
                )
            )

        override suspend fun report(
            request: ApiRequest<JsonObject>
        ): Response<ApiResponse<JsonObject>> =
            throw UnsupportedOperationException()
    }
}
