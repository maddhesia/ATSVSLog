package com.sma.atsvslog.repository

import androidx.room.withTransaction
import com.sma.atsvslog.database.ATSVSLogDatabase
import com.sma.atsvslog.database.dao.MasterConflictDao
import com.sma.atsvslog.database.entity.DailyCounterEntity
import com.sma.atsvslog.database.entity.MasterConflictEntity
import com.sma.atsvslog.database.entity.MasterEntity
import com.sma.atsvslog.database.entity.SyncQueueEntity
import com.sma.atsvslog.database.entity.TransactionEntity
import com.sma.atsvslog.database.entity.TransactionItemEntity
import com.sma.atsvslog.network.SyncPayloadFactory
import com.sma.atsvslog.network.dto.EVENT_TYPE_MASTER
import com.sma.atsvslog.network.dto.EVENT_TYPE_MASTER_RECLASSIFY
import com.sma.atsvslog.network.dto.EVENT_TYPE_SALE
import com.sma.atsvslog.network.dto.EVENT_TYPE_SALE_CORRECTION
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

data class ConflictSaleEdit(
    val conflict: MasterConflictEntity,
    val item: TransactionItemEntity,
    val itemCount: Int
)

class LocalSalesRepository(
    private val database: ATSVSLogDatabase,
    private val onQueueEvent: (() -> Unit)? = null
) {
    private val transactionDao = database.transactionDao()
    private val itemDao = database.transactionItemDao()
    private val counterDao = database.dailyCounterDao()
    private val masterDao = database.masterDao()
    private val syncQueueDao = database.syncQueueDao()
    private val conflictDao: MasterConflictDao = database.masterConflictDao()

    fun observeTransactions(date: String): Flow<List<TransactionEntity>> =
        transactionDao.observeByDate(date)

    fun observeTransactionItems(transactionUuid: String): Flow<List<TransactionItemEntity>> =
        itemDao.observeForTransaction(transactionUuid)

    fun observeDailyCounter(date: String): Flow<DailyCounterEntity?> =
        counterDao.observe(date)

    fun observeOldestPendingMasterConflict(): Flow<MasterConflictEntity?> =
        conflictDao.observeOldestPending()

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

    /**
     * Saves the item locally first. When allowModelConflict is true, a local
     * Model ownership conflict is deliberately retained as a durable conflict
     * record rather than rejecting the sale. This path is used only when the
     * authoritative online Masters check was unavailable (offline/degraded
     * network).
     */
    suspend fun saveItem(
        transactionUuid: String,
        draft: SaleItemDraft,
        now: Long = System.currentTimeMillis(),
        allowModelConflict: Boolean = false,
        trustCloudOwnership: Boolean = false
    ) {
        require(transactionDao.findByUuid(transactionUuid) != null) {
            "Transaction does not exist: $transactionUuid"
        }

        var masterQueued = false

        database.withTransaction {
            val assignments = masterDao.findModelAssignments(draft.model)
            val conflictingAssignment = assignments.firstOrNull {
                normalize(it.type) != normalize(draft.type) ||
                    normalize(it.brand) != normalize(draft.brand)
            }

            if (conflictingAssignment != null && trustCloudOwnership) {
                masterDao.reclassifyModel(
                    model = draft.model,
                    newType = draft.type,
                    newBrand = draft.brand
                )
            } else if (conflictingAssignment != null && !allowModelConflict) {
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

            if (conflictingAssignment != null && trustCloudOwnership) {
                enqueueMasterMutation(item, now)
                masterQueued = true
            } else if (conflictingAssignment != null) {
                conflictDao.insert(
                    MasterConflictEntity(
                        conflictUuid = UUID.randomUUID().toString(),
                        transactionUuid = transactionUuid,
                        itemUuid = item.itemUuid,
                        model = draft.model,
                        requestedType = draft.type,
                        requestedBrand = draft.brand,
                        canonicalType = conflictingAssignment.type,
                        canonicalBrand = conflictingAssignment.brand,
                        createdAt = now
                    )
                )
                // Schedule the existing network-constrained worker. It will
                // wake when connectivity returns and surface the conflict.
                masterQueued = true
            } else if (upsertMasterFromSale(item, now)) {
                enqueueMasterMutation(item, now)
                masterQueued = true
            }
        }

        if (masterQueued) {
            onQueueEvent?.invoke()
        }
    }

    /**
     * Online conflict resolution: change the current local Master ownership,
     * create a durable correction event, save the new item, and leave all
     * historical TransactionItem rows untouched.
     */
    suspend fun reclassifyAndSaveItem(
        transactionUuid: String,
        draft: SaleItemDraft,
        canonicalType: String,
        canonicalBrand: String,
        now: Long = System.currentTimeMillis()
    ) {
        var queued = false

        database.withTransaction {
            require(transactionDao.findByUuid(transactionUuid) != null) {
                "Transaction does not exist: $transactionUuid"
            }

            val assignments = masterDao.findModelAssignments(draft.model)
            val existingOwner = assignments.firstOrNull()

            if (existingOwner != null) {
                val localIsCanonical =
                    normalize(existingOwner.type) == normalize(canonicalType) &&
                        normalize(existingOwner.brand) == normalize(canonicalBrand)
                val localIsRequested =
                    normalize(existingOwner.type) == normalize(draft.type) &&
                        normalize(existingOwner.brand) == normalize(draft.brand)

                require(localIsCanonical || localIsRequested) {
                    "Catalogue ownership changed before the conflict was resolved. Refresh and try again."
                }
                masterDao.reclassifyModel(
                    model = draft.model,
                    newType = draft.type,
                    newBrand = draft.brand
                )
            }

            enqueueMasterReclassification(
                model = draft.model,
                oldType = canonicalType,
                oldBrand = canonicalBrand,
                newType = draft.type,
                newBrand = draft.brand,
                now = now
            )

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
            queued = true
        }

        if (queued) {
            onQueueEvent?.invoke()
        }
    }

    /**
     * Completes the customer transaction. A sale belonging to an unresolved
     * Model conflict is intentionally NOT queued yet. It becomes uploadable
     * only after every conflict for that transaction has been resolved.
     */
    suspend fun finishCustomer(
        transactionUuid: String,
        now: Long = System.currentTimeMillis()
    ) {
        var queued = false

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

            transactionDao.update(transaction.copy(completedAt = now))

            ensureCounter(transaction.transactionDate, now)
            counterDao.incrementConversions(transaction.transactionDate, now)

            if (conflictDao.countPendingForTransaction(transactionUuid) == 0) {
                enqueueSale(transaction, items, now)
                queued = true
            }
        }

        if (queued) {
            onQueueEvent?.invoke()
        }
    }

    /**
     * Starts correction of a completed transaction that contains a pending
     * Model conflict. The conversion is temporarily removed while the sale
     * is being corrected; the transaction becomes incomplete again.
     *
     * This never changes the canonical Master ownership.
     */
    suspend fun prepareConflictSaleForEditing(
        conflictLocalId: Long,
        now: Long = System.currentTimeMillis()
    ): ConflictSaleEdit {
        return database.withTransaction {
            val conflict = conflictDao.findPendingById(conflictLocalId)
                ?: error("Pending Model conflict not found: $conflictLocalId")

            val transaction = transactionDao.findByUuid(conflict.transactionUuid)
                ?: error("Transaction does not exist: ${conflict.transactionUuid}")

            val item = conflict.itemUuid?.let { itemDao.findByUuid(it) }
                ?: itemDao.findForTransaction(conflict.transactionUuid).firstOrNull {
                    normalize(it.model) == normalize(conflict.model) &&
                        normalize(it.type) == normalize(conflict.requestedType) &&
                        normalize(it.brand) == normalize(conflict.requestedBrand)
                }
                ?: error("Conflicting sale item could not be found.")

            if (transaction.completedAt != null) {
                ensureCounter(transaction.transactionDate, now)
                check(counterDao.decrementConversions(transaction.transactionDate, now) > 0) {
                    "Unable to temporarily remove the conversion for the conflicting sale."
                }
                transactionDao.update(transaction.copy(completedAt = null))
            }

            ConflictSaleEdit(
                conflict = conflict,
                item = item,
                itemCount = itemDao.findForTransaction(conflict.transactionUuid).size
            )
        }
    }

    /**
     * Abandons the current correction session without resolving the conflict.
     * The transaction becomes completed again and the conversion is restored;
     * the conflict remains pending and eligible for another notification.
     */
    suspend fun cancelConflictSaleEditing(
        conflictLocalId: Long,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            val conflict = conflictDao.findPendingById(conflictLocalId)
                ?: return@withTransaction
            val transaction = transactionDao.findByUuid(conflict.transactionUuid)
                ?: return@withTransaction

            if (transaction.completedAt == null) {
                transactionDao.update(transaction.copy(completedAt = now))
                ensureCounter(transaction.transactionDate, now)
                counterDao.incrementConversions(transaction.transactionDate, now)
            }

            conflictDao.clearNotification(conflictLocalId)
        }
    }

    /**
     * Updates the existing conflicting TransactionItem in place. No new item
     * row is created, so a correction cannot duplicate merchandise.
     */
    suspend fun updateConflictSaleItem(
        conflictLocalId: Long,
        draft: SaleItemDraft
    ) {
        database.withTransaction {
            val conflict = conflictDao.findPendingById(conflictLocalId)
                ?: error("Pending Model conflict not found: $conflictLocalId")

            val item = conflict.itemUuid?.let { itemDao.findByUuid(it) }
                ?: itemDao.findForTransaction(conflict.transactionUuid).firstOrNull {
                    normalize(it.model) == normalize(conflict.model) &&
                        normalize(it.type) == normalize(conflict.requestedType) &&
                        normalize(it.brand) == normalize(conflict.requestedBrand)
                }
                ?: error("Conflicting sale item could not be found.")

            itemDao.update(
                item.copy(
                    type = draft.type,
                    brand = draft.brand,
                    model = draft.model,
                    size = draft.size,
                    colour = draft.colour,
                    sellingPrice = draft.sellingPrice
                )
            )
        }
    }

    /**
     * Finalizes a corrected conflicting sale. The corrected TransactionItem
     * replaces the erroneous local row, the pending conflict is resolved,
     * Conversion is restored exactly once, and cloud delivery uses either:
     *   - SALE when the original sale never reached the cloud (offline case),
     *   - SALE_CORRECTION when a server-side conflict caused an earlier SALE
     *     to fail.
     *
     * Canonical Master ownership is never changed by this operation.
     */
    suspend fun finishConflictSaleEdit(
        conflictLocalId: Long,
        draft: SaleItemDraft,
        now: Long = System.currentTimeMillis()
    ) {
        var queued = false

        database.withTransaction {
            val conflict = conflictDao.findPendingById(conflictLocalId)
                ?: error("Pending Model conflict not found: $conflictLocalId")

            val transaction = transactionDao.findByUuid(conflict.transactionUuid)
                ?: error("Transaction does not exist: ${conflict.transactionUuid}")

            require(transaction.completedAt == null) {
                "Conflict sale must be in correction mode before it is finished."
            }

            val item = conflict.itemUuid?.let { itemDao.findByUuid(it) }
                ?: itemDao.findForTransaction(conflict.transactionUuid).firstOrNull {
                    normalize(it.model) == normalize(conflict.model) &&
                        normalize(it.type) == normalize(conflict.requestedType) &&
                        normalize(it.brand) == normalize(conflict.requestedBrand)
                }
                ?: error("Conflicting sale item could not be found.")

            val assignments = masterDao.findModelAssignments(draft.model)
            val conflictingAssignment = assignments.firstOrNull {
                normalize(it.type) != normalize(draft.type) ||
                    normalize(it.brand) != normalize(draft.brand)
            }

            if (conflictingAssignment != null) {
                throw IllegalArgumentException(
                    "The corrected sale still conflicts with Model ${draft.model}: " +
                        "${conflictingAssignment.brand} / ${conflictingAssignment.type}."
                )
            }

            itemDao.update(
                item.copy(
                    type = draft.type,
                    brand = draft.brand,
                    model = draft.model,
                    size = draft.size,
                    colour = draft.colour,
                    sellingPrice = draft.sellingPrice
                )
            )

            val correctedItem = item.copy(
                type = draft.type,
                brand = draft.brand,
                model = draft.model,
                size = draft.size,
                colour = draft.colour,
                sellingPrice = draft.sellingPrice
            )
            val masterWasCreated = upsertMasterFromSale(correctedItem, now)

            conflictDao.markResolved(conflict.localId, now)

            require(conflictDao.countPendingForTransaction(conflict.transactionUuid) == 0) {
                "Resolve all Model conflicts for this customer before finishing the corrected sale."
            }

            if (conflict.blockedQueueLocalId != null) {
                syncQueueDao.markFailedEventResolved(
                    queueLocalId = conflict.blockedQueueLocalId,
                    attemptedAt = now
                )
                syncQueueDao.markPendingSaleEventsResolvedForTransaction(
                    transactionUuid = conflict.transactionUuid,
                    attemptedAt = now
                )
            }

            val completedAt = now
            transactionDao.update(transaction.copy(completedAt = completedAt))
            ensureCounter(transaction.transactionDate, now)
            counterDao.incrementConversions(transaction.transactionDate, now)

            if (masterWasCreated) {
                enqueueMasterMutation(correctedItem, now)
            }

            if (conflict.blockedQueueLocalId != null) {
                enqueueSaleCorrection(
                    transaction = transaction.copy(completedAt = completedAt),
                    item = correctedItem,
                    now = now
                )
            } else {
                enqueueSale(
                    transaction = transaction.copy(completedAt = completedAt),
                    items = itemDao.findForTransaction(conflict.transactionUuid),
                    now = now
                )
            }

            queued = true
        }

        if (queued) {
            onQueueEvent?.invoke()
        }
    }

    /**
     * Resolves one conflict in FIFO order. Current local Master rows are
     * reclassified, a MASTER_RECLASSIFY correction is queued, the conflict is
     * marked resolved, and a completed transaction's SALE is queued only when
     * no other conflicts remain for that transaction.
     */
    suspend fun resolveMasterConflict(
        conflictLocalId: Long,
        now: Long = System.currentTimeMillis()
    ) {
        var queued = false

        database.withTransaction {
            val conflict = conflictDao.findPendingById(conflictLocalId)
                ?: return@withTransaction

            masterDao.reclassifyModel(
                model = conflict.model,
                newType = conflict.requestedType,
                newBrand = conflict.requestedBrand
            )

            val correction = SyncPayloadFactory.createMasterReclassificationPayload(
                model = conflict.model,
                oldType = conflict.canonicalType,
                oldBrand = conflict.canonicalBrand,
                newType = conflict.requestedType,
                newBrand = conflict.requestedBrand
            )

            syncQueueDao.insert(
                SyncQueueEntity(
                    eventUuid = correction.first,
                    eventType = EVENT_TYPE_MASTER_RECLASSIFY,
                    payload = correction.second,
                    status = SYNC_STATUS_PENDING,
                    createdAt = conflict.createdAt,
                    lastAttemptAt = null,
                    attemptCount = 0,
                    lastErrorCode = null
                )
            )

            conflictDao.markResolved(conflict.localId, now)

            if (conflict.blockedQueueLocalId != null) {
                syncQueueDao.markFailedEventResolved(
                    queueLocalId = conflict.blockedQueueLocalId,
                    attemptedAt = now
                )
            }

            if (conflictDao.countPendingForTransaction(conflict.transactionUuid) == 0) {
                val transaction = transactionDao.findByUuid(conflict.transactionUuid)
                if (transaction?.completedAt != null) {
                    val items = itemDao.findForTransaction(conflict.transactionUuid)
                    if (items.isNotEmpty()) {
                        enqueueSale(
                            transaction = transaction,
                            items = items,
                            now = maxOf(now, transaction.completedAt)
                        )
                    }
                }
            }

            queued = true
        }

        if (queued) {
            onQueueEvent?.invoke()
        }
    }

    /**
     * Converts a server-side MASTER_CONFLICT into the same durable conflict
     * workflow. This protects the multi-device race where the local cache did
     * not yet know the canonical Model owner.
     */
    suspend fun recordServerMasterConflict(
        event: SyncQueueEntity,
        canonicalType: String,
        canonicalBrand: String,
        requestedType: String,
        requestedBrand: String,
        model: String,
        itemUuid: String? = null,
        transactionUuid: String,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            val existing = conflictDao.findPending(
                transactionUuid = transactionUuid,
                model = model,
                requestedType = requestedType,
                requestedBrand = requestedBrand
            )

            if (existing == null) {
                conflictDao.insert(
                    MasterConflictEntity(
                        conflictUuid = UUID.randomUUID().toString(),
                        transactionUuid = transactionUuid,
                        itemUuid = itemUuid,
                        model = model,
                        requestedType = requestedType,
                        requestedBrand = requestedBrand,
                        canonicalType = canonicalType,
                        canonicalBrand = canonicalBrand,
                        createdAt = event.createdAt,
                        blockedQueueLocalId = event.queueLocalId
                    )
                )
            }
        }
    }

    suspend fun getPendingMasterConflict(
        localId: Long
    ): MasterConflictEntity? = conflictDao.findPendingById(localId)

    suspend fun getPendingMasterConflictCount(
        transactionUuid: String
    ): Int = conflictDao.countPendingForTransaction(transactionUuid)

    suspend fun markConflictNotificationShown(
        localId: Long,
        now: Long = System.currentTimeMillis()
    ) {
        conflictDao.markNotified(localId, now)
    }

    suspend fun addWalkIn(
        date: String,
        now: Long = System.currentTimeMillis()
    ) {
        var queued = false

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

            queued = true
        }

        if (queued) {
            onQueueEvent?.invoke()
        }
    }

    suspend fun removeWalkIn(
        date: String,
        now: Long = System.currentTimeMillis()
    ) {
        var queued = false

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

                queued = true
            }
        }

        if (queued) {
            onQueueEvent?.invoke()
        }
    }

    suspend fun resetWalkIns(
        date: String,
        now: Long = System.currentTimeMillis()
    ) {
        var queued = false

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

            queued = true
        }

        if (queued) {
            onQueueEvent?.invoke()
        }
    }

    suspend fun findLastSellingPrice(
        model: String,
        size: String,
        colour: String
    ): Long? =
        itemDao.findLatestForPrice(model, size, colour)?.sellingPrice

    fun observeTypes(): Flow<List<String>> = masterDao.observeTypes()

    fun observeModels(type: String, brand: String): Flow<List<String>> =
        masterDao.observeModels(type, brand)

    fun observeSizes(model: String): Flow<List<String>> =
        masterDao.observeSizes(model)

    fun observeColours(model: String): Flow<List<String>> =
        masterDao.observeColours(model)

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
    ): Boolean {
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
            return true
        }

        masterDao.update(
            existing.copy(
                lastSellingPrice = item.sellingPrice,
                lastSoldAt = now
            )
        )
        return false
    }

    private suspend fun enqueueMasterMutation(
        item: TransactionItemEntity,
        now: Long
    ) {
        val payload = SyncPayloadFactory.createMasterMutationPayload(
            type = item.type,
            brand = item.brand,
            model = item.model,
            size = item.size,
            colour = item.colour,
            transactionUuid = item.transactionUuid,
            itemUuid = item.itemUuid
        )

        syncQueueDao.insert(
            SyncQueueEntity(
                eventUuid = payload.first,
                eventType = EVENT_TYPE_MASTER,
                payload = payload.second,
                status = SYNC_STATUS_PENDING,
                createdAt = now,
                lastAttemptAt = null,
                attemptCount = 0,
                lastErrorCode = null
            )
        )
    }

    private suspend fun enqueueMasterReclassification(
        model: String,
        oldType: String,
        oldBrand: String,
        newType: String,
        newBrand: String,
        now: Long
    ) {
        val payload = SyncPayloadFactory.createMasterReclassificationPayload(
            model = model,
            oldType = oldType,
            oldBrand = oldBrand,
            newType = newType,
            newBrand = newBrand
        )

        syncQueueDao.insert(
            SyncQueueEntity(
                eventUuid = payload.first,
                eventType = EVENT_TYPE_MASTER_RECLASSIFY,
                payload = payload.second,
                status = SYNC_STATUS_PENDING,
                createdAt = now,
                lastAttemptAt = null,
                attemptCount = 0,
                lastErrorCode = null
            )
        )
    }

    private suspend fun enqueueSale(
        transaction: TransactionEntity,
        items: List<TransactionItemEntity>,
        now: Long
    ) {
        val (eventUuid, payload) = SyncPayloadFactory.createSalePayload(
            transactionUuid = transaction.transactionUuid,
            transactionDate = transaction.transactionDate,
            completedAt = transaction.completedAt ?: now,
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

    private suspend fun enqueueSaleCorrection(
        transaction: TransactionEntity,
        item: TransactionItemEntity,
        now: Long
    ) {
        val (eventUuid, payload) = SyncPayloadFactory.createSaleCorrectionPayload(
            transactionUuid = transaction.transactionUuid,
            transactionDate = transaction.transactionDate,
            completedAt = transaction.completedAt ?: now,
            item = item
        )

        syncQueueDao.insert(
            SyncQueueEntity(
                eventUuid = eventUuid,
                eventType = EVENT_TYPE_SALE_CORRECTION,
                payload = payload,
                status = SYNC_STATUS_PENDING,
                createdAt = now,
                lastAttemptAt = null,
                attemptCount = 0,
                lastErrorCode = null
            )
        )
    }

    private fun normalize(value: String): String = value.trim().lowercase()
}

private const val SYNC_STATUS_PENDING = "Pending"
