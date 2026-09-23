package com.sma.atsvslog.features.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.sma.atsvslog.network.AtSvsApi

/**
 * M15 share boundary.
 *
 * The existing Apps Script Daily Report remains the single source of report
 * truth. This class only fetches that report, renders it to a PNG, and hands
 * the PNG to Android's standard share mechanism. WhatsApp can consume the
 * image from the Sharesheet without requiring a WhatsApp-specific API.
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

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                "ATSVSLog Daily Sales Report — ${report.reportDate}"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(
                intent,
                "Share Daily Report"
            )
        )
    }
}
