package com.sma.atsvslog.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsModelsTest {
    @Test
    fun encodeDecodeLogs_roundTripsCorrelationFields() {
        val original = listOf(
            DiagnosticLogEntry(
                timestamp = 1000L,
                level = "INFO",
                event = "SYNC_SUCCESS",
                message = "SALE accepted",
                requestId = "request-1",
                eventUuid = "event-1"
            )
        )

        val decoded = decodeLogs(encodeLogs(original))

        assertEquals(original, decoded)
    }

    @Test
    fun decodeMalformedLogs_returnsEmptyList() {
        assertTrue(decodeLogs("not-json").isEmpty())
    }

    @Test
    fun lastError_roundTripsCorrelationFields() {
        val original = DiagnosticLastError(
            timestamp = 2000L,
            code = "MASTER_CONFLICT",
            message = "conflict",
            requestId = "request-2",
            eventUuid = "event-2"
        )

        val decoded = decodeLastError(encodeLastError(original))

        assertEquals(original, decoded)
    }
}
