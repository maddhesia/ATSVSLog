package com.sma.atsvslog.repository

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.sma.atsvslog.database.ATSVSLogDatabase
import com.sma.atsvslog.network.dto.WALK_IN_INCREMENT
import com.sma.atsvslog.network.dto.WALK_IN_RESET
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class LocalSalesRepositorySyncQueueTest {

    private lateinit var database: ATSVSLogDatabase
    private lateinit var repository: LocalSalesRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        database = Room.inMemoryDatabaseBuilder(
            context,
            ATSVSLogDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

        repository = LocalSalesRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun finishCustomer_createsDurableSaleQueueEventAtomically() = runBlocking {
        val transactionUuid = repository.startTransaction(
            date = "2026-08-23",
            now = 1_000L
        )

        repository.saveItem(
            transactionUuid = transactionUuid,
            draft = SaleItemDraft(
                type = "Duffle Bag",
                brand = "Kamiliant",
                model = "Raptor",
                size = "M",
                colour = "Black",
                sellingPrice = 1599L
            ),
            now = 2_000L
        )

        repository.finishCustomer(
            transactionUuid = transactionUuid,
            now = 3_000L
        )

        val transaction =
            database.transactionDao().findByUuid(transactionUuid)

        assertNotNull(transaction?.completedAt)

        val first = database.syncQueueDao().findOldestPending()

        assertNotNull(first)
        assertEquals("MASTER", first!!.eventType)
        assertEquals("Pending", first.status)
        assertEquals(0, first.attemptCount)
        assertNotNull(first.eventUuid)

        val masterJson = Gson().fromJson(
            first.payload,
            Map::class.java
        )
        assertEquals("MASTER", masterJson["eventType"])
        assertEquals("Raptor", masterJson["model"])

        database.syncQueueDao().update(first.copy(status = "Synced"))

        val queued = database.syncQueueDao().findOldestPending()
        assertNotNull(queued)
        assertEquals("SALE", queued!!.eventType)
        assertEquals("Pending", queued.status)
        assertEquals(0, queued.attemptCount)
        assertNotNull(queued.eventUuid)

        val json = Gson().fromJson(
            queued.payload,
            Map::class.java
        )

        assertEquals("SALE", json["eventType"])
        assertEquals(transactionUuid, json["transactionUuid"])
        assertEquals("2026-08-23", json["transactionDate"])
        assertNotNull(json["completedAt"])
        assertTrue((json["items"] as List<*>).isNotEmpty())
    }

    @Test
    fun saveItem_newMaster_createsDurableMasterQueueEvent() = runBlocking {
        val transactionUuid = repository.startTransaction(
            date = "2026-08-23",
            now = 10_000L
        )

        repository.saveItem(
            transactionUuid = transactionUuid,
            draft = SaleItemDraft(
                type = "Duffle Bag",
                brand = "Kamiliant",
                model = "NewModel",
                size = "55",
                colour = "Blue",
                sellingPrice = 2000L
            ),
            now = 11_000L
        )

        val queued = database.syncQueueDao().findOldestPending()
        assertNotNull(queued)
        assertEquals("MASTER", queued!!.eventType)

        val json = Gson().fromJson(queued.payload, Map::class.java)
        assertEquals("MASTER", json["eventType"])
        assertEquals("Duffle Bag", json["type"])
        assertEquals("Kamiliant", json["brand"])
        assertEquals("NewModel", json["model"])
        assertEquals("55", json["size"])
        assertEquals("Blue", json["colour"])
    }

    @Test
    fun addWalkIn_createsIncrementQueueEvent() = runBlocking {
        repository.addWalkIn(
            date = "2026-08-23",
            now = 4_000L
        )

        val queued = database.syncQueueDao().findOldestPending()

        assertNotNull(queued)
        assertEquals("WALK_IN", queued!!.eventType)

        val json = Gson().fromJson(
            queued.payload,
            Map::class.java
        )

        assertEquals(WALK_IN_INCREMENT, json["operation"])
        assertEquals(1.0, json["delta"])
        assertEquals(1.0, json["resultingWalkIns"])
    }

    @Test
    fun resetWalkIns_createsResetQueueEvent() = runBlocking {
        repository.addWalkIn(
            date = "2026-08-23",
            now = 4_000L
        )
        repository.resetWalkIns(
            date = "2026-08-23",
            now = 5_000L
        )

        val first = database.syncQueueDao().findOldestPending()
        assertNotNull(first)

        val json = Gson().fromJson(
            first!!.payload,
            Map::class.java
        )

        assertEquals(WALK_IN_INCREMENT, json["operation"])

        // The reset event is the second FIFO event. Its presence is verified
        // by temporarily marking the first event Synced.
        database.syncQueueDao().update(
            first.copy(status = "Synced")
        )

        val second = database.syncQueueDao().findOldestPending()
        assertNotNull(second)

        val resetJson = Gson().fromJson(
            second!!.payload,
            Map::class.java
        )

        assertEquals(WALK_IN_RESET, resetJson["operation"])
        assertEquals(0.0, resetJson["delta"])
        assertEquals(0.0, resetJson["resultingWalkIns"])
    }
}
