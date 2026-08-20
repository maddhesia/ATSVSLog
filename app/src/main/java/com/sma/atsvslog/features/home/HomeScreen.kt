package com.sma.atsvslog.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
    onRecordSale: () -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 28.dp, vertical = 16.dp)
            .navigationBarsPadding()
    ) {
        Text(
            text = "AT SVS Log",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.padding(top = 24.dp))

        OutlinedButton(
            onClick = { showDatePicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Date: $date",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.padding(top = 24.dp))

        Text(
            text = "Footfall: $walkIns",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.padding(top = 20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRemoveWalkIn,
                modifier = Modifier.weight(1f)
            ) {
                Text("-1")
            }

            Button(
                onClick = onAddWalkIn,
                modifier = Modifier.weight(1f)
            ) {
                Text("+1")
            }

            OutlinedButton(
                onClick = onResetWalkIns,
                modifier = Modifier.weight(2f)
            ) {
                Text("Reset Walk-ins")
            }
        }

        Spacer(modifier = Modifier.padding(top = 28.dp))

        Text(
            text = "Conversions: $conversions",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onRecordSale,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors = ButtonDefaults.buttonColors()
        ) {
            Text("RECORD SALE")
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
