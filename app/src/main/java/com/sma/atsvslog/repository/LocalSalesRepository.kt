package com.sma.atsvslog.repository

import androidx.room.withTransaction
import com.sma.atsvslog.database.ATSVSLogDatabase
import com.sma.atsvslog.database.entity.DailyCounterEntity
import com.sma.atsvslog.database.entity.MasterEntity
import com.sma.atsvslog.database.entity.SyncQueueEntity
import com.sma.atsvslog.database.entity.TransactionEntity
import com.sma.atsvslog.database.entity.TransactionItemEntity
import com.sma.atsvslog.network.SyncPayloadFactory
import com.sma.atsvslog.network.dto.EVENT_TYPE_SALE
import com.sma.atsvslog.network.dto.EVENT_TYPE_WALK_IN
import kotlinx.coroutines.flow.Flow
import java.util.UUID

data class SaleItemDraft(
    val type: String,
    val brand: String,
    val model: String,
    val size: String,
    val colour: String,
    val sellingPrice: Long
)

class LocalSalesRepository(
    private val database: ATSVSLogDatabase
) {
    private val transactionDao = database.transactionDao()
    private val itemDao = database.transactionItemDao()
    private val counterDao = database.dailyCounterDao()
    private val masterDao = database.masterDao()
    private val syncQueueDao = database.syncQueueDao()

    fun observeTransactions(date: String): Flow<List<TransactionEntity>> =
        transactionDao.observeByDate(date)

    fun observeTransactionItems(transactionUuid: String): Flow<List<TransactionItemEntity>> =
        itemDao.observeForTransaction(transactionUuid)

    fun observeDailyCounter(date: String): Flow<DailyCounterEntity?> =
        counterDao.observe(date)

    suspend fun startTransaction(
        date: String,
        now: Long = System.currentTimeMillis()
    ): String {
        val uuid = UUID.randomUUID().toString()
        database.withTransaction {
            transactionDao.insert(
                TransactionEntity(
                    transactionUuid = uuid,
                    transactionDate = date,
                    createdAt = now,
                    completedAt = null
                )
            )
        }
        return uuid
    }

    suspend fun saveItem(
        transactionUuid: String,
        draft: SaleItemDraft,
        now: Long = System.currentTimeMillis()
    ) {
        require(transactionDao.findByUuid(transactionUuid) != null) {
            "Transaction does not exist: $transactionUuid"
        }

        database.withTransaction {
            val assignments = masterDao.findModelAssignments(draft.model)
            val conflictingAssignment = assignments.firstOrNull {
                it.type != draft.type || it.brand != draft.brand
            }
            if (conflictingAssignment != null) {
                throw IllegalArgumentException(
                    "Model \"${draft.model}\" already belongs to " +
                        "${conflictingAssignment.brand} ${conflictingAssignment.type}. " +
                        "Each model name must represent one merchandise only."
                )
            }

            val item = TransactionItemEntity(
                itemUuid = UUID.randomUUID().toString(),
                transactionUuid = transactionUuid,
                type = draft.type,
                brand = draft.brand,
                model = draft.model,
                size = draft.size,
                colour = draft.colour,
                sellingPrice = draft.sellingPrice
            )

            itemDao.insert(item)
            upsertMasterFromSale(item, now)
        }
    }

    /**
     * Completes the customer transaction and, in the SAME Room transaction,
     * creates the durable SALE SyncQueue event.
     *
     * No network call occurs here.
     */
    suspend fun finishCustomer(
        transactionUuid: String,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            val transaction = transactionDao.findByUuid(transactionUuid)
                ?: error("Transaction does not exist: $transactionUuid")

            require(transaction.completedAt == null) {
                "Transaction is already completed: $transactionUuid"
            }

            val items = itemDao.findForTransaction(transactionUuid)
            require(items.isNotEmpty()) {
                "Cannot finish a customer without at least one saved item."
            }

            transactionDao.update(
                transaction.copy(completedAt = now)
            )

            ensureCounter(transaction.transactionDate, now)
            counterDao.incrementConversions(transaction.transactionDate, now)

            val (eventUuid, payload) = SyncPayloadFactory.createSalePayload(
                transactionUuid = transaction.transactionUuid,
                transactionDate = transaction.transactionDate,
                completedAt = now,
                items = items
            )

            syncQueueDao.insert(
                SyncQueueEntity(
                    eventUuid = eventUuid,
                    eventType = EVENT_TYPE_SALE,
                    payload = payload,
                    status = SYNC_STATUS_PENDING,
                    createdAt = now,
                    lastAttemptAt = null,
                    attemptCount = 0,
                    lastErrorCode = null
                )
            )
        }
    }

    suspend fun addWalkIn(
        date: String,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            ensureCounter(date, now)
            counterDao.changeWalkIns(date, 1, now)

            val resultingWalkIns = counterDao.find(date)?.walkIns
                ?: error("Daily counter disappeared during walk-in increment.")

            val (eventUuid, payload) =
                SyncPayloadFactory.createWalkInIncrementPayload(
                    businessDate = date,
                    resultingWalkIns = resultingWalkIns
                )

            syncQueueDao.insert(
                SyncQueueEntity(
                    eventUuid = eventUuid,
                    eventType = EVENT_TYPE_WALK_IN,
                    payload = payload,
                    status = SYNC_STATUS_PENDING,
                    createdAt = now,
                    lastAttemptAt = null,
                    attemptCount = 0,
                    lastErrorCode = null
                )
            )
        }
    }

    suspend fun removeWalkIn(
        date: String,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            ensureCounter(date, now)

            val current = counterDao.find(date) ?: return@withTransaction

            if (current.walkIns > 0) {
                counterDao.changeWalkIns(date, -1, now)

                val resultingWalkIns = counterDao.find(date)?.walkIns
                    ?: error("Daily counter disappeared during walk-in decrement.")

                val (eventUuid, payload) =
                    SyncPayloadFactory.createWalkInDecrementPayload(
                        businessDate = date,
                        resultingWalkIns = resultingWalkIns
                    )

                syncQueueDao.insert(
                    SyncQueueEntity(
                        eventUuid = eventUuid,
                        eventType = EVENT_TYPE_WALK_IN,
                        payload = payload,
                        status = SYNC_STATUS_PENDING,
                        createdAt = now,
                        lastAttemptAt = null,
                        attemptCount = 0,
                        lastErrorCode = null
                    )
                )
            }
        }
    }

    suspend fun resetWalkIns(
        date: String,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            ensureCounter(date, now)
            counterDao.resetWalkIns(date, now)

            val (eventUuid, payload) =
                SyncPayloadFactory.createWalkInResetPayload(
                    businessDate = date
                )

            syncQueueDao.insert(
                SyncQueueEntity(
                    eventUuid = eventUuid,
                    eventType = EVENT_TYPE_WALK_IN,
                    payload = payload,
                    status = SYNC_STATUS_PENDING,
                    createdAt = now,
                    lastAttemptAt = null,
                    attemptCount = 0,
                    lastErrorCode = null
                )
            )
        }
    }

    suspend fun findLastSellingPrice(
        model: String,
        size: String,
        colour: String
    ): Long? =
        itemDao.findLatestForPrice(model, size, colour)?.sellingPrice

    fun observeTypes(): Flow<List<String>> =
        masterDao.observeTypes()

    fun observeModels(type: String, brand: String): Flow<List<String>> =
        masterDao.observeModels(type, brand)

    fun observeSizes(model: String): Flow<List<String>> =
        masterDao.observeSizes(model)

    fun observeColours(model: String): Flow<List<String>> =
        masterDao.observeColours(model)

    /**
     * Development-only catalogue cleanup used for the Milestone 4 test database.
     *
     * Keeps one canonical master row per model, choosing the most recently
     * sold/updated assignment when legacy test data contains conflicting
     * Type + Brand ownership. Existing transaction history is untouched.
     *
     * Size and Colour are deliberately reset to "Enter New" so the next
     * test run can build the variant catalogue from a clean state.
     */
    suspend fun resetDevelopmentMasterVariants() {
        database.withTransaction {
            val masters = masterDao.getAllMasters()

            val canonicalByModel = masters
                .groupBy { it.model.trim().lowercase() }
                .values
                .mapNotNull { rows ->
                    rows.maxWithOrNull(
                        compareBy<MasterEntity> {
                            it.lastSoldAt ?: Long.MIN_VALUE
                        }.thenBy { it.localId }
                    )
                }

            masterDao.deleteAll()

            canonicalByModel.forEach { master ->
                masterDao.insert(
                    master.copy(
                        localId = 0,
                        size = ENTER_NEW,
                        colour = ENTER_NEW
                    )
                )
            }
        }
    }

    private suspend fun ensureCounter(date: String, now: Long) {
        counterDao.insertIfMissing(
            DailyCounterEntity(
                date = date,
                updatedAt = now
            )
        )
    }

    private suspend fun upsertMasterFromSale(
        item: TransactionItemEntity,
        now: Long
    ) {
        val existing = masterDao.findCombination(
            type = item.type,
            brand = item.brand,
            model = item.model,
            size = item.size,
            colour = item.colour
        )

        if (existing == null) {
            masterDao.insert(
                MasterEntity(
                    type = item.type,
                    brand = item.brand,
                    model = item.model,
                    size = item.size,
                    colour = item.colour,
                    lastSellingPrice = item.sellingPrice,
                    lastSoldAt = now
                )
            )
        } else {
            masterDao.update(
                existing.copy(
                    lastSellingPrice = item.sellingPrice,
                    lastSoldAt = now
                )
            )
        }
    }
}

private const val SYNC_STATUS_PENDING = "Pending"
private const val ENTER_NEW = "Enter New"
