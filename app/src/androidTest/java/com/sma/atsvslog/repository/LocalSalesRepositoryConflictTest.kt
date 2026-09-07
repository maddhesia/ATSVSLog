package com.sma.atsvslog.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.sma.atsvslog.database.ATSVSLogDatabase
import com.sma.atsvslog.database.entity.MasterConflictEntity
import com.sma.atsvslog.database.entity.MasterEntity
import com.sma.atsvslog.database.entity.SyncQueueEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalSalesRepositoryConflictTest {

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
    fun editSale_cancelsMasterReassignmentAndRestoresConversion() = runBlocking {
        database.masterDao().insert(
            MasterEntity(
                type = "Trolley Bag",
                brand = "American Tourister",
                model = "Diamo",
                size = "55",
                colour = "Black",
                lastSellingPrice = 2000L,
                lastSoldAt = 1L
            )
        )

        val transactionUuid = repository.startTransaction(
            date = "2026-09-04",
            now = 2_000L
        )

        repository.saveItem(
            transactionUuid = transactionUuid,
            draft = SaleItemDraft(
                type = "Trolley Bag",
                brand = "Kamiliant",
                model = "Diamo",
                size = "55",
                colour = "Blue",
                sellingPrice = 2500L
            ),
            now = 3_000L,
            allowModelConflict = true
        )
        repository.finishCustomer(transactionUuid, now = 4_000L)

        val conflict = repository.observeOldestPendingMasterConflict().first()
        assertNotNull(conflict)
        assertEquals("American Tourister", conflict!!.canonicalBrand)
        assertEquals("Kamiliant", conflict.requestedBrand)

        val counterBefore = database.dailyCounterDao().find("2026-09-04")
        assertEquals(1, counterBefore!!.conversions)

        val edit = repository.prepareConflictSaleForEditing(conflict.localId, now = 5_000L)
        assertEquals("Diamo", edit.item.model)
        assertEquals("Kamiliant", edit.item.brand)

        val counterDuringEdit = database.dailyCounterDao().find("2026-09-04")
        assertEquals(0, counterDuringEdit!!.conversions)
        assertEquals(null, database.transactionDao().findByUuid(transactionUuid)!!.completedAt)

        repository.updateConflictSaleItem(
            conflictLocalId = conflict.localId,
            draft = SaleItemDraft(
                type = "Trolley Bag",
                brand = "American Tourister",
                model = "Diamo",
                size = "55",
                colour = "Blue",
                sellingPrice = 2500L
            )
        )
        repository.finishConflictSaleEdit(
            conflictLocalId = conflict.localId,
            draft = SaleItemDraft(
                type = "Trolley Bag",
                brand = "American Tourister",
                model = "Diamo",
                size = "55",
                colour = "Blue",
                sellingPrice = 2500L
            ),
            now = 6_000L
        )

        val counterAfter = database.dailyCounterDao().find("2026-09-04")
        assertEquals(1, counterAfter!!.conversions)
        assertNotNull(database.transactionDao().findByUuid(transactionUuid)!!.completedAt)
        assertEquals(
            "American Tourister",
            database.transactionItemDao().findForTransaction(transactionUuid).single().brand
        )
        assertEquals(
            "American Tourister",
            database.masterDao().getAllMasters().single().brand
        )
        assertEquals(null, database.masterConflictDao().findPendingById(conflict.localId))

        val queued = database.syncQueueDao().findOldestPending()
        assertNotNull(queued)
        assertEquals("SALE", queued!!.eventType)
    }

    @Test
    fun serverConflictCorrection_usesSaleCorrectionWithoutDuplicatingItem() = runBlocking {
        database.masterDao().insert(
            MasterEntity(
                type = "Trolley Bag",
                brand = "American Tourister",
                model = "Diamo",
                size = "55",
                colour = "Black",
                lastSellingPrice = 2000L,
                lastSoldAt = 1L
            )
        )

        val transactionUuid = repository.startTransaction("2026-09-04", 2_000L)
        repository.saveItem(
            transactionUuid = transactionUuid,
            draft = SaleItemDraft(
                type = "Trolley Bag",
                brand = "Kamiliant",
                model = "Diamo",
                size = "55",
                colour = "Blue",
                sellingPrice = 2500L
            ),
            now = 3_000L,
            allowModelConflict = true
        )
        repository.finishCustomer(transactionUuid, now = 4_000L)
        val item = database.transactionItemDao().findForTransaction(transactionUuid).single()

        val (eventUuid, payload) = com.sma.atsvslog.network.SyncPayloadFactory.createSalePayload(
            transactionUuid = transactionUuid,
            transactionDate = "2026-09-04",
            completedAt = 4_000L,
            items = listOf(item)
        )
        val failedQueueId = database.syncQueueDao().insert(
            SyncQueueEntity(
                eventUuid = eventUuid,
                eventType = "SALE",
                payload = payload,
                status = "Failed",
                createdAt = 4_000L,
                lastAttemptAt = 4_100L,
                attemptCount = 1,
                lastErrorCode = "MASTER_CONFLICT"
            )
        )
        database.masterConflictDao().insert(
            MasterConflictEntity(
                conflictUuid = "conflict-1",
                transactionUuid = transactionUuid,
                itemUuid = item.itemUuid,
                model = "Diamo",
                requestedType = "Trolley Bag",
                requestedBrand = "Kamiliant",
                canonicalType = "Trolley Bag",
                canonicalBrand = "American Tourister",
                createdAt = 4_100L,
                blockedQueueLocalId = failedQueueId,
                notifiedAt = 4_200L
            )
        )
        val conflict = database.masterConflictDao().findPendingById(1L)!!

        repository.prepareConflictSaleForEditing(conflict.localId, 5_000L)
        repository.updateConflictSaleItem(
            conflict.localId,
            SaleItemDraft("Trolley Bag", "American Tourister", "Diamo", "55", "Blue", 2500L)
        )
        repository.finishConflictSaleEdit(
            conflict.localId,
            SaleItemDraft("Trolley Bag", "American Tourister", "Diamo", "55", "Blue", 2500L),
            6_000L
        )

        assertEquals(1, database.transactionItemDao().findForTransaction(transactionUuid).size)
        assertEquals("American Tourister", database.transactionItemDao().findForTransaction(transactionUuid).single().brand)
        assertEquals("Synced", database.syncQueueDao().findByEventUuid(eventUuid)!!.status)

        val correction = database.syncQueueDao().findOldestPending()
        assertNotNull(correction)
        assertEquals("SALE_CORRECTION", correction!!.eventType)
        val json = Gson().fromJson(correction.payload, Map::class.java)
        assertEquals(item.itemUuid, (json["item"] as Map<*, *>)["itemUuid"])
    }

    @Test
    fun reassignModel_stillChangesMasterButDoesNotChangeConversion() = runBlocking {
        database.masterDao().insert(
            MasterEntity(
                type = "Trolley Bag",
                brand = "American Tourister",
                model = "Diamo",
                size = "55",
                colour = "Black",
                lastSellingPrice = 2000L,
                lastSoldAt = 1L
            )
        )
        val transactionUuid = repository.startTransaction("2026-09-04", 2_000L)
        repository.saveItem(
            transactionUuid,
            SaleItemDraft("Trolley Bag", "Kamiliant", "Diamo", "55", "Blue", 2500L),
            3_000L,
            allowModelConflict = true
        )
        repository.finishCustomer(transactionUuid, 4_000L)
        val conflict = repository.observeOldestPendingMasterConflict().first()!!

        repository.resolveMasterConflict(conflict.localId, 5_000L)

        assertEquals(1, database.dailyCounterDao().find("2026-09-04")!!.conversions)
        assertTrue(database.masterDao().getAllMasters().single().brand == "Kamiliant")
        assertEquals(null, repository.observeOldestPendingMasterConflict().first())
    }
}
