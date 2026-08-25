package com.sma.atsvslog.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sma.atsvslog.BetaNetwork
import com.sma.atsvslog.di.DatabaseProvider
import com.sma.atsvslog.repository.LocalSyncRepository

/**
 * Thin WorkManager adapter around the independently testable SyncEngine.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val database = DatabaseProvider.get(applicationContext)
        val queue = LocalSyncRepository(database)

        Log.i(TAG, "Sync worker started")

        return when (
            SyncEngine(
                queue = queue,
                send = { request ->
                    BetaNetwork.client.api.sync(request)
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
                // The failed item is durably marked Failed. WorkManager itself
                // has completed successfully; the periodic safety net can
                // process any later Pending events after the failed item is
                // addressed.
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
