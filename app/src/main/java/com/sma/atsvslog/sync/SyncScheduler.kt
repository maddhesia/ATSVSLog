package com.sma.atsvslog.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Central scheduling point for the single logical Beta sync worker.
 */
object SyncScheduler {

    private val networkConstraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    fun enqueueImmediate(context: Context) {
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                SyncWorker.IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
    }

    fun ensurePeriodic(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(networkConstraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                SyncWorker.PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }
}
