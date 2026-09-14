package com.sma.atsvslog.diagnostics

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

const val DIAGNOSTICS_LOGS_KEY = "diagnostics.logs"
const val DIAGNOSTICS_LAST_SUCCESS_KEY = "diagnostics.last_success_at"
const val DIAGNOSTICS_LAST_ERROR_KEY = "diagnostics.last_error"

private val LOG_LIST_TYPE = object : TypeToken<List<DiagnosticLogEntry>>() {}.type
private val gson = Gson()

data class DiagnosticLogEntry(
    val timestamp: Long,
    val level: String,
    val event: String,
    val message: String,
    val requestId: String? = null,
    val eventUuid: String? = null
)

data class DiagnosticLastError(
    val timestamp: Long,
    val code: String,
    val message: String,
    val requestId: String? = null,
    val eventUuid: String? = null
)

data class DiagnosticsSnapshot(
    val appVersion: String,
    val dbVersion: Int,
    val apiVersion: Int,
    val networkAvailable: Boolean,
    val workerState: String,
    val pendingCount: Int,
    val failedCount: Int,
    val lastSuccessfulSyncAt: Long?,
    val lastError: DiagnosticLastError?,
    val recentLogs: List<DiagnosticLogEntry>
)

internal fun encodeLogs(logs: List<DiagnosticLogEntry>): String =
    gson.toJson(logs)

internal fun decodeLogs(value: String?): List<DiagnosticLogEntry> =
    value?.let {
        runCatching {
            gson.fromJson<List<DiagnosticLogEntry>>(it, LOG_LIST_TYPE) ?: emptyList()
        }.getOrDefault(emptyList())
    } ?: emptyList()

internal fun encodeLastError(error: DiagnosticLastError): String =
    gson.toJson(error)

internal fun decodeLastError(value: String?): DiagnosticLastError? =
    value?.let {
        runCatching {
            gson.fromJson(it, DiagnosticLastError::class.java)
        }.getOrNull()
    }
