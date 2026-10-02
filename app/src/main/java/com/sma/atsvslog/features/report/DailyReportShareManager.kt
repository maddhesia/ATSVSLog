package com.sma.atsvslog.features.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.sma.atsvslog.network.AtSvsApi
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * M17 share boundary.
 *
 * Fetches the selected report from the existing Apps Script report endpoint,
 * renders the approved presentation card, and hands the image to Android's
 * standard Sharesheet.
 */
object DailyReportShareManager {

    suspend fun share(
        context: Context,
        api: AtSvsApi,
        date: String
    ) {
        val report = ReportRepository(api).fetchReport(date)
        val file = DailyReportCardRenderer.render(context, report)

        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)

        val caption = "Store Sales Report for ${formatCaptionDate(report.reportDate)}"

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(
                intent,
                "Share Daily Report"
            )
        )
    }

    private fun formatCaptionDate(value: String): String {
        val date = LocalDate.parse(value)
        val day = date.dayOfMonth
        val suffix = when {
            day in 11..13 -> "th"
            day % 10 == 1 -> "st"
            day % 10 == 2 -> "nd"
            day % 10 == 3 -> "rd"
            else -> "th"
        }

        val monthAndYear = date.format(
            DateTimeFormatter.ofPattern("MMMM, yyyy", Locale.ENGLISH)
        )
        return "$day$suffix $monthAndYear"
    }
}
