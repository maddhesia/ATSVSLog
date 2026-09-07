package com.sma.atsvslog.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sma.atsvslog.BetaNetwork
import com.sma.atsvslog.di.DatabaseProvider
import com.sma.atsvslog.notifications.MasterConflictNotificationManager
import com.sma.atsvslog.repository.LocalSalesRepository
import com.sma.atsvslog.repository.LocalSyncRepository
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first

/**
 * Thin WorkManager adapter around the independently testable SyncEngine.
 *
 * M12 also surfaces any durable unresolved Model conflict when connectivity
 * is available. The notification never resolves or mutates the business data;
 * the Room conflict record remains the source of truth.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val database = DatabaseProvider.get(applicationContext)
        val queue = LocalSyncRepository(database)
        val salesRepository = LocalSalesRepository(database)

        Log.i(TAG, "Sync worker started")

        val pendingConflict =
            salesRepository.observeOldestPendingMasterConflict().first()

        if (pendingConflict != null && pendingConflict.notifiedAt == null) {
            if (MasterConflictNotificationManager.notify(
                    applicationContext,
                    pendingConflict
                )
            ) {
                salesRepository.markConflictNotificationShown(pendingConflict.localId)
            }
        }

        return when (
            SyncEngine(
                queue = queue,
                send = { request ->
                    BetaNetwork.client.api.sync(request)
                },
                isBlockedByMasterConflict = { event ->
                    val transactionUuid = runCatching {
                        JsonParser.parseString(event.payload)
                            .asJsonObject
                            .get("transactionUuid")
                            ?.asString
                            ?.trim()
                            .orEmpty()
                    }.getOrDefault("")
                    transactionUuid.isNotBlank() &&
                        salesRepository.getPendingMasterConflictCount(transactionUuid) > 0
                },
                onMasterConflict = { event, details ->
                    salesRepository.recordServerMasterConflict(
                        event = event,
                        canonicalType = details.canonicalType,
                        canonicalBrand = details.canonicalBrand,
                        requestedType = details.requestedType,
                        requestedBrand = details.requestedBrand,
                        model = details.model,
                        itemUuid = details.itemUuid.ifBlank { null },
                        transactionUuid = details.transactionUuid
                    )
                    val conflict =
                        salesRepository.observeOldestPendingMasterConflict().first()
                    if (conflict != null && conflict.notifiedAt == null) {
                        if (MasterConflictNotificationManager.notify(
                                applicationContext,
                                conflict
                            )
                        ) {
                            salesRepository.markConflictNotificationShown(conflict.localId)
                        }
                    }
                }
            ).run()
        ) {
            SyncRunResult.Drained -> {
                Log.i(TAG, "Sync worker drained Pending queue")
                Result.success()
            }

            SyncRunResult.Retry -> {
                Log.w(TAG, "Sync worker paused after temporary failure")
                Result.retry()
            }

            SyncRunResult.StoppedAfterPermanentFailure -> {
                Log.e(
                    TAG,
                    "Sync worker stopped after permanent queue failure"
                )
                Result.success()
            }

            SyncRunResult.BlockedByMasterConflict -> {
                Log.i(
                    TAG,
                    "Sync worker paused because an unresolved Model conflict blocks the pending event"
                )
                Result.success()
            }
        }
    }

    companion object {
        const val IMMEDIATE_WORK_NAME = "ATSVSLog.SyncWorker.Immediate"
        const val PERIODIC_WORK_NAME = "ATSVSLog.SyncWorker.Periodic"

        private const val TAG = "ATSVS_SYNC"
    }
}
