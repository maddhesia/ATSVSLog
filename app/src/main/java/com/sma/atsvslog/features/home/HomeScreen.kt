package com.sma.atsvslog.features.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    date: String,
    walkIns: Int,
    conversions: Int,
    onDateSelected: (String) -> Unit,
    onAddWalkIn: () -> Unit,
    onRemoveWalkIn: () -> Unit,
    onResetWalkIns: () -> Unit,
    onRecordSale: () -> Unit,
    onViewReport: () -> Unit,
    onShareReport: () -> Unit,
    shareReportInProgress: Boolean,
    onOpenDiagnostics: () -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "AT SVS Log",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures( onLongPress = { onOpenDiagnostics() } )

            }
        )

        OutlinedButton(
            onClick = { showDatePicker = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = "Date: $date",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Text(
            text = "Footfall: $walkIns",
            style = MaterialTheme.typography.headlineSmall
        )

        OutlinedButton(
            onClick = onResetWalkIns,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Reset Walk-ins")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRemoveWalkIn,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text("-1")
            }

            OutlinedButton(
                onClick = onAddWalkIn,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text("+1")
            }
        }

        Text(
            text = "Conversions: $conversions",
            style = MaterialTheme.typography.headlineSmall
        )

        OutlinedButton(
            onClick = onRecordSale,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("RECORD SALE")
        }

        OutlinedButton(
            onClick = onViewReport,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("VIEW DAILY REPORT")
        }

        OutlinedButton(
            onClick = onShareReport,
            enabled = !shareReportInProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                if (shareReportInProgress) {
                    "PREPARING REPORT…"
                } else {
                    "SHARE DAILY REPORT"
                }
            )
        }
    }

    if (showDatePicker) {
        val selectedMillis = runCatching {
            LocalDate.parse(date)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()

        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedMillis
        )

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = datePickerState.selectedDateMillis != null,
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val selectedDate = Instant
                                .ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toString()
                            onDateSelected(selectedDate)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("CANCEL")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
