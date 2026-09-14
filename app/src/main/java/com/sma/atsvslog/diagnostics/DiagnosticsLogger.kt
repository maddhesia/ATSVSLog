package com.sma.atsvslog.diagnostics

import com.sma.atsvslog.repository.LocalSettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface DiagnosticsLogger {
    suspend fun log(
        level: String,
        event: String,
        message: String,
        requestId: String? = null,
        eventUuid: String? = null
    )
}

class RoomDiagnosticsLogger(
    private val settings: LocalSettingsRepository,
    private val now: () -> Long = System::currentTimeMillis
) : DiagnosticsLogger {

    private val mutex = Mutex()

    override suspend fun log(
        level: String,
        event: String,
        message: String,
        requestId: String?,
        eventUuid: String?
    ) {
        mutex.withLock {
            val timestamp = now()
            val safeMessage = sanitize(message)
            val entry = DiagnosticLogEntry(
                timestamp = timestamp,
                level = level,
                event = event,
                message = safeMessage,
                requestId = requestId,
                eventUuid = eventUuid
            )

            val logs = decodeLogs(settings.get(DIAGNOSTICS_LOGS_KEY))
                .takeLast(MAX_LOG_ENTRIES - 1) + entry

            settings.put(
                key = DIAGNOSTICS_LOGS_KEY,
                value = encodeLogs(logs),
                now = timestamp
            )

            if (event == "SYNC_SUCCESS" || event == "SYNC_IDEMPOTENT_SUCCESS") {
                settings.put(
                    key = DIAGNOSTICS_LAST_SUCCESS_KEY,
                    value = timestamp.toString(),
                    now = timestamp
                )
            }

            if (level == "ERROR") {
                settings.put(
                    key = DIAGNOSTICS_LAST_ERROR_KEY,
                    value = encodeLastError(
                        DiagnosticLastError(
                            timestamp = timestamp,
                            code = event,
                            message = safeMessage,
                            requestId = requestId,
                            eventUuid = eventUuid
                        )
                    ),
                    now = timestamp
                )
            }
        }
    }

    private fun sanitize(message: String): String =
        message
            .replace(
                Regex("apiKey=[^&\\s]+", RegexOption.IGNORE_CASE),
                "apiKey=<redacted>"
            )
            .replace(
                Regex("X-API-Key[:=]\\s*[^\\s]+", RegexOption.IGNORE_CASE),
                "X-API-Key=<redacted>"
            )

    companion object {
        private const val MAX_LOG_ENTRIES = 100
    }
}

object NoOpDiagnosticsLogger : DiagnosticsLogger {
    override suspend fun log(
        level: String,
        event: String,
        message: String,
        requestId: String?,
        eventUuid: String?
    ) = Unit
}
