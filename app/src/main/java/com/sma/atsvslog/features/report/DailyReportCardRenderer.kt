package com.sma.atsvslog.features.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Creates the stakeholder-facing daily report image used by M15 sharing.
 *
 * The renderer deliberately consumes the existing DailyReport model. It does
 * not read Room, Sheets, or raw ledgers and therefore cannot become a second
 * reporting engine.
 */
object DailyReportCardRenderer {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350
    private const val MARGIN = 64f
    private const val GAP = 28f

    private val moneyFormat = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
        maximumFractionDigits = 0
        minimumFractionDigits = 0
    }

    private val dateFormatter = DateTimeFormatter.ofPattern(
        "dd MMM yyyy",
        Locale.ENGLISH
    )

    fun render(
        context: Context,
        report: DailyReport
    ): File {
        val bitmap = Bitmap.createBitmap(
            WIDTH,
            HEIGHT,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val accent = Color.rgb(98, 0, 238)
        val accentDark = Color.rgb(55, 0, 179)
        val text = Color.rgb(32, 32, 36)
        val muted = Color.rgb(100, 100, 108)
        val border = Color.rgb(224, 224, 230)
        val panel = Color.rgb(248, 247, 252)

        val titlePaint = paint(
            size = 42f,
            color = Color.WHITE,
            bold = true
        )
        val subtitlePaint = paint(
            size = 25f,
            color = Color.WHITE
        )
        val sectionPaint = paint(
            size = 28f,
            color = accentDark,
            bold = true
        )
        val labelPaint = paint(
            size = 23f,
            color = muted
        )
        val valuePaint = paint(
            size = 31f,
            color = text,
            bold = true
        )
        val bodyPaint = paint(
            size = 22f,
            color = text
        )
        val smallPaint = paint(
            size = 19f,
            color = muted
        )

        // Header
        canvas.drawRect(
            0f,
            0f,
            WIDTH.toFloat(),
            190f,
            Paint().apply { color = accent }
        )
        canvas.drawText(
            "DAILY SALES REPORT",
            MARGIN,
            70f,
            titlePaint
        )
        canvas.drawText(
            report.storeName,
            MARGIN,
            112f,
            subtitlePaint
        )
        canvas.drawText(
            "${report.location}  •  ${formatDate(report.reportDate)}",
            MARGIN,
            151f,
            subtitlePaint
        )

        var y = 225f

        y = drawPanel(
            canvas,
            y,
            165f,
            panel,
            border
        )
        canvas.drawText("TODAY'S SALES", MARGIN + 24f, y + 42f, sectionPaint)
        drawMetric(canvas, "American Tourister", money(report.atSales), 340f, y + 90f, labelPaint, valuePaint)
        drawMetric(canvas, "Kamiliant", money(report.kamSales), 700f, y + 90f, labelPaint, valuePaint)
        drawMetric(canvas, "TOTAL", money(report.totalSales), 340f, y + 138f, labelPaint, valuePaint)
        y += 165f + GAP

        y = drawPanel(canvas, y, 155f, panel, border)
        canvas.drawText("FOOTFALL & CONVERSION", MARGIN + 24f, y + 40f, sectionPaint)
        drawMetric(canvas, "Walk-ins", report.footfall.toString(), 340f, y + 88f, labelPaint, valuePaint)
        drawMetric(canvas, "Conversions", report.conversions.toString(), 700f, y + 88f, labelPaint, valuePaint)
        drawMetric(canvas, "Conversion %", percent(report.conversionPercent), 340f, y + 132f, labelPaint, valuePaint)
        y += 155f + GAP

        y = drawPanel(canvas, y, 360f, panel, border)
        canvas.drawText("MERCHANDISE SOLD", MARGIN + 24f, y + 40f, sectionPaint)

        val leftX = MARGIN + 24f
        val rightX = 560f
        val columnWidth = 430
        val itemStartY = y + 78f

        canvas.drawText(
            "AMERICAN TOURISTER (${report.merchandiseSold.americanTourister.size})",
            leftX,
            itemStartY,
            smallPaint.apply { typeface = Typeface.DEFAULT_BOLD }
        )
        canvas.drawText(
            "KAMILIANT (${report.merchandiseSold.kamiliant.size})",
            rightX,
            itemStartY,
            smallPaint.apply { typeface = Typeface.DEFAULT_BOLD }
        )

        drawItems(
            canvas,
            report.merchandiseSold.americanTourister,
            leftX,
            itemStartY + 25f,
            columnWidth,
            bodyPaint,
            smallPaint
        )
        drawItems(
            canvas,
            report.merchandiseSold.kamiliant,
            rightX,
            itemStartY + 25f,
            columnWidth,
            bodyPaint,
            smallPaint
        )

        y += 360f + GAP

        y = drawPanel(canvas, y, 245f, panel, border)
        canvas.drawText("MONTH TO DATE", MARGIN + 24f, y + 40f, sectionPaint)
        drawMetric(canvas, "AT", money(report.monthToDate.atSales), 340f, y + 88f, labelPaint, valuePaint)
        drawMetric(canvas, "Kamiliant", money(report.monthToDate.kamSales), 700f, y + 88f, labelPaint, valuePaint)
        drawMetric(canvas, "Total", money(report.monthToDate.totalSales), 340f, y + 136f, labelPaint, valuePaint)
        drawMetric(canvas, "Footfall", report.monthToDate.footfall.toString(), 700f, y + 136f, labelPaint, valuePaint)
        drawMetric(canvas, "Conversions", report.monthToDate.conversions.toString(), 340f, y + 184f, labelPaint, valuePaint)
        drawMetric(canvas, "Conversion %", percent(report.monthToDate.conversionPercent), 700f, y + 184f, labelPaint, valuePaint)
        y += 245f

        // Footer
        canvas.drawLine(
            MARGIN,
            HEIGHT - 55f,
            WIDTH - MARGIN,
            HEIGHT - 55f,
            Paint().apply {
                color = border
                strokeWidth = 2f
            }
        )
        canvas.drawText(
            "ATSVSLog  •  Generated from the current Daily Report",
            MARGIN,
            HEIGHT - 24f,
            smallPaint
        )

        val directory = File(context.cacheDir, "report_cards").apply {
            mkdirs()
        }
        val safeDate = report.reportDate.replace(Regex("[^0-9-]"), "_")
        val output = File(directory, "ATSVSLog_Daily_Report_$safeDate.png")

        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Unable to encode the daily report image."
            }
        }
        bitmap.recycle()

        return output
    }

    private fun drawPanel(
        canvas: Canvas,
        top: Float,
        height: Float,
        fill: Int,
        border: Int
    ): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fill
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            MARGIN,
            top,
            WIDTH - MARGIN,
            top + height,
            24f,
            24f,
            paint
        )
        paint.color = border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRoundRect(
            MARGIN,
            top,
            WIDTH - MARGIN,
            top + height,
            24f,
            24f,
            paint
        )
        return top
    }

    private fun drawMetric(
        canvas: Canvas,
        label: String,
        value: String,
        x: Float,
        y: Float,
        labelPaint: Paint,
        valuePaint: Paint
    ) {
        canvas.drawText(label, x, y, labelPaint)
        canvas.drawText(value, x, y + 30f, valuePaint)
    }

    private fun drawItems(
        canvas: Canvas,
        items: List<ReportItem>,
        x: Float,
        startY: Float,
        width: Int,
        bodyPaint: Paint,
        smallPaint: Paint
    ) {
        if (items.isEmpty()) {
            canvas.drawText("No merchandise recorded.", x, startY + 10f, smallPaint)
            return
        }

        var y = startY
        items.take(8).forEachIndexed { index, item ->
            val title = listOf(item.model, item.size)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("  ")
            val detail = listOf(item.colour.trim(), money(item.sellingPrice))
                .filter { it.isNotEmpty() }
                .joinToString("  •  ")

            y = drawWrappedText(
                canvas,
                "${index + 1}. $title",
                x,
                y,
                width,
                bodyPaint
            )
            y = drawWrappedText(
                canvas,
                detail,
                x + 28f,
                y + 3f,
                width - 28,
                smallPaint
            ) + 15f
        }

        if (items.size > 8) {
            canvas.drawText(
                "+ ${items.size - 8} more item(s)",
                x,
                y,
                smallPaint
            )
        }
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        width: Int,
        paint: Paint
    ): Float {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paint.color
            textSize = paint.textSize
            typeface = paint.typeface
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, width)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.0f)
            .build()

        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()

        return y + layout.height
    }

    private fun paint(
        size: Float,
        color: Int,
        bold: Boolean = false
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun money(value: Long): String =
        "₹${moneyFormat.format(value)}"

    private fun percent(value: Double): String =
        String.format(Locale.US, "%.1f%%", value)

    private fun formatDate(value: String): String =
        runCatching {
            LocalDate.parse(value).format(dateFormatter)
        }.getOrElse { value }
}
