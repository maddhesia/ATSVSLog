package com.sma.atsvslog.features.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ReportScreen(
    state: ReportUiState,
    onReload: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) {
                Text("BACK")
            }

            TextButton(
                onClick = onReload,
                enabled = !state.isLoading
            ) {
                Text("REFRESH")
            }
        }

        Text(
            text = "Daily Report",
            style = MaterialTheme.typography.headlineMedium
        )

        when {
            state.isLoading -> {
                Text("Loading report…")
            }

            state.errorMessage != null -> {
                Text(
                    text = state.errorMessage,
                    color = MaterialTheme.colorScheme.error
                )
            }

            state.report != null -> {
                val report = state.report

                Text(
                    text = report.storeName,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "${report.location} • ${report.reportDate}",
                    style = MaterialTheme.typography.bodyMedium
                )

                HorizontalDivider()

                Text(
                    text = "Today's Sales",
                    style = MaterialTheme.typography.titleMedium
                )

                MetricRow("AT", money(report.atSales))
                MetricRow("Kamiliant", money(report.kamSales))
                MetricRow("Total", money(report.totalSales))

                HorizontalDivider()

                Text(
                    text = "Footfall & Conversion",
                    style = MaterialTheme.typography.titleMedium
                )

                MetricRow("Footfall", report.footfall.toString())
                MetricRow("Conversions", report.conversions.toString())
                MetricRow(
                    "Conversion %",
                    String.format(
                        java.util.Locale.US,
                        "%.1f%%",
                        report.conversionPercent
                    )
                )

                HorizontalDivider()

                MerchandiseSection(
                    title = "American Tourister",
                    items = report.merchandiseSold.americanTourister
                )

                MerchandiseSection(
                    title = "Kamiliant",
                    items = report.merchandiseSold.kamiliant
                )

                HorizontalDivider()

                Text(
                    text = "Month Till Date",
                    style = MaterialTheme.typography.titleMedium
                )

                MetricRow("AT", money(report.monthToDate.atSales))
                MetricRow(
                    "Kamiliant",
                    money(report.monthToDate.kamSales)
                )
                MetricRow(
                    "Total",
                    money(report.monthToDate.totalSales)
                )
                MetricRow(
                    "Footfall",
                    report.monthToDate.footfall.toString()
                )
                MetricRow(
                    "Conversions",
                    report.monthToDate.conversions.toString()
                )

                if (report.insights.isNotEmpty()) {
                    HorizontalDivider()

                    Text(
                        text = "Today's Insights",
                        style = MaterialTheme.typography.titleMedium
                    )

                    report.insights.forEach { insight ->
                        Text("• $insight")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("BACK TO HOME")
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun MerchandiseSection(
    title: String,
    items: List<ReportItem>
) {
    Text(
        text = "$title (${items.size})",
        style = MaterialTheme.typography.titleMedium
    )

    if (items.isEmpty()) {
        Text("No merchandise recorded.")
        return
    }

    items.forEach { item ->
        Text(
            text = "${item.model} • ${item.size} • " +
                "${item.colour} • ${money(item.sellingPrice)}"
        )
    }
}

private fun money(value: Long): String =
    "₹$value"
