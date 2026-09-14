package com.sma.atsvslog.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun DiagnosticsScreen(
    snapshot: DiagnosticsSnapshot?,
    onRefresh: () -> Unit,
    onClearLogs: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Diagnostics",
            style = MaterialTheme.typography.headlineSmall
        )

        snapshot?.let { data ->
            DiagnosticCard("Application") {
                DiagnosticRow("App version", data.appVersion)
                DiagnosticRow("DB version", data.dbVersion.toString())
                DiagnosticRow("API version", data.apiVersion.toString())
            }

            DiagnosticCard("Connectivity") {
                DiagnosticRow(
                    "Network",
                    if (data.networkAvailable) "AVAILABLE" else "OFFLINE"
                )
            }

            DiagnosticCard("Synchronization") {
                DiagnosticRow("Worker", data.workerState)
                DiagnosticRow("Pending", data.pendingCount.toString())
                DiagnosticRow("Failed", data.failedCount.toString())
                DiagnosticRow(
                    "Last successful sync",
                    formatTimestamp(data.lastSuccessfulSyncAt)
                )
            }

            DiagnosticCard("Last error") {
                DiagnosticRow("Code", data.lastError?.code ?: "None")
                DiagnosticRow("Message", data.lastError?.message ?: "None")
                DiagnosticRow("Time", formatTimestamp(data.lastError?.timestamp))
                DiagnosticRow(
                    "Request ID",
                    data.lastError?.requestId ?: "None"
                )
                DiagnosticRow(
                    "Event UUID",
                    data.lastError?.eventUuid ?: "None"
                )
            }

            DiagnosticCard("Recent activity") {
                if (data.recentLogs.isEmpty()) {
                    Text("No diagnostic events recorded.")
                } else {
                    data.recentLogs.forEach { log ->
                        Text(
                            text = buildString {
                                append(formatTimestamp(log.timestamp))
                                append("  ")
                                append(log.level)
                                append("  ")
                                append(log.event)
                                append("\n")
                                append(log.message)
                                log.requestId?.let {
                                    append("\nrequestId=")
                                    append(it)
                                }
                                log.eventUuid?.let {
                                    append("\neventUuid=")
                                    append(it)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        } ?: Text("Loading diagnostics…")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text("BACK")
            }

            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                Text("REFRESH")
            }
        }

        Button(
            onClick = onClearLogs,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("CLEAR RECENT LOGS")
        }

        Spacer(modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun DiagnosticCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text(value)
    }
}

private fun formatTimestamp(timestamp: Long?): String {
    if (timestamp == null) return "None"
    return DateFormat
        .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(timestamp))
}
