package com.sma.atsvslog.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.sma.atsvslog.database.ATSVSLogDatabase
import com.sma.atsvslog.network.dto.API_VERSION
import com.sma.atsvslog.repository.LocalSettingsRepository
import com.sma.atsvslog.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiagnosticsRepository(
    private val context: Context,
    private val database: ATSVSLogDatabase
) {
    private val settings = LocalSettingsRepository(database)
    private val workManager = WorkManager.getInstance(context.applicationContext)

    suspend fun snapshot(): DiagnosticsSnapshot = withContext(Dispatchers.IO) {
        val queue = database.syncQueueDao()
        val pendingCount = queue.countPending()
        val failedCount = queue.countFailed()

        val lastSuccess = settings
            .get(DIAGNOSTICS_LAST_SUCCESS_KEY)
            ?.toLongOrNull()

        val lastError = decodeLastError(
            settings.get(DIAGNOSTICS_LAST_ERROR_KEY)
        )

        val logs = decodeLogs(
            settings.get(DIAGNOSTICS_LOGS_KEY)
        ).takeLast(50).reversed()

        DiagnosticsSnapshot(
            appVersion = appVersion(),
            dbVersion = 3,
            apiVersion = API_VERSION,
            networkAvailable = isNetworkAvailable(),
            workerState = currentWorkerState(),
            pendingCount = pendingCount,
            failedCount = failedCount,
            lastSuccessfulSyncAt = lastSuccess,
            lastError = lastError,
            recentLogs = logs
        )
    }

    suspend fun clearRecentLogs() = withContext(Dispatchers.IO) {
        settings.put(
            key = DIAGNOSTICS_LOGS_KEY,
            value = "[]"
        )
    }

    private fun appVersion(): String =
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            ?: "unknown"

    private fun isNetworkAvailable(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun currentWorkerState(): String {
        val immediate = workManager
            .getWorkInfosForUniqueWork(SyncWorker.IMMEDIATE_WORK_NAME)
            .get()
        val periodic = workManager
            .getWorkInfosForUniqueWork(SyncWorker.PERIODIC_WORK_NAME)
            .get()

        val candidates = buildList {
            addAll(immediate.map { "IMMEDIATE" to it })
            addAll(periodic.map { "PERIODIC" to it })
        }

        if (candidates.isEmpty()) return "NOT SCHEDULED"

        val priority = listOf(
            WorkInfo.State.RUNNING,
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED,
            WorkInfo.State.SUCCEEDED
        )

        val selected = candidates.minByOrNull {
            priority.indexOf(it.second.state).let { index ->
                if (index < 0) Int.MAX_VALUE else index
            }
        } ?: return "NOT SCHEDULED"

        return "${selected.first}: ${selected.second.state.name}"
    }
}
