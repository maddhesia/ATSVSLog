package com.sma.atsvslog.repository

import androidx.room.withTransaction
import com.sma.atsvslog.database.ATSVSLogDatabase
import com.sma.atsvslog.database.entity.DailyCounterEntity
import com.sma.atsvslog.database.entity.MasterEntity
import com.sma.atsvslog.database.entity.TransactionEntity
import com.sma.atsvslog.database.entity.TransactionItemEntity
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

            require(itemDao.findForTransaction(transactionUuid).isNotEmpty()) {
                "Cannot finish a customer without at least one saved item."
            }

            transactionDao.update(
                transaction.copy(completedAt = now)
            )

            ensureCounter(transaction.transactionDate, now)
            counterDao.incrementConversions(transaction.transactionDate, now)
        }
    }

    suspend fun addWalkIn(
        date: String,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            ensureCounter(date, now)
            counterDao.changeWalkIns(date, 1, now)
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

    fun observeModels(brand: String): Flow<List<String>> =
        masterDao.observeModels(brand)

    fun observeSizes(brand: String, model: String): Flow<List<String>> =
        masterDao.observeSizes(brand, model)

    fun observeColours(): Flow<List<String>> =
        masterDao.observeColours()

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
