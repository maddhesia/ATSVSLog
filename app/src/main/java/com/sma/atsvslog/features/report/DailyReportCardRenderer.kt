package com.sma.atsvslog.features.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.sma.atsvslog.R
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/**
 * Creates the stakeholder-facing Daily Report image used by sharing.
 *
 * M17 presentation pass:
 * - Uses one of the four approved original abstract artworks at random.
 * - Keeps the artwork subordinate to the report content with a dark scrim
 *   and translucent report panels.
 * - Reorganizes the report into spacious KPI, merchandise and MTD blocks.
 * - Uses the selected report date for date-sensitive section wording.
 *
 * The renderer consumes only DailyReport and does not read Room, Sheets or
 * raw ledgers.
 */
object DailyReportCardRenderer {

    private const val WIDTH = 1080
    private const val BASE_HEIGHT = 1600
    private const val MARGIN = 64f
    private const val GAP = 24f
    private const val PANEL_RADIUS = 28f

    private val moneyFormat = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
        maximumFractionDigits = 0
        minimumFractionDigits = 0
    }

    private val artworkIds = intArrayOf(
        R.drawable.report_artwork_1,
        R.drawable.report_artwork_2,
        R.drawable.report_artwork_3,
        R.drawable.report_artwork_4
    )

    fun render(
        context: Context,
        report: DailyReport
    ): File {
        val maxItems = max(
            report.merchandiseSold.americanTourister.size,
            report.merchandiseSold.kamiliant.size
        )
        val merchandiseHeight = max(230f, 128f + maxItems * 64f)
        val height = BASE_HEIGHT + max(0, maxItems - 6) * 64

        val bitmap = Bitmap.createBitmap(
            WIDTH,
            height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)

        drawArtworkBackground(context, canvas, width = WIDTH, height = height)
        drawOverlay(canvas, WIDTH, height)

        val white = Color.WHITE
        val softWhite = Color.argb(225, 255, 255, 255)
        val panelFill = Color.argb(202, 6, 12, 30)
        val panelStroke = Color.argb(95, 255, 255, 255)
        val accent = Color.rgb(76, 196, 255)
        val muted = Color.argb(205, 225, 232, 245)
        val value = Color.WHITE

        val titlePaint = paint(46f, white, true)
        val subtitlePaint = paint(25f, softWhite)
        val sectionPaint = paint(27f, accent, true)
        val labelPaint = paint(22f, muted)
        val valuePaint = paint(34f, value, true)
        val totalPaint = paint(42f, value, true)
        val bodyPaint = paint(22f, softWhite)
        val smallPaint = paint(18f, muted)
        val footerPaint = paint(17f, Color.argb(185, 225, 232, 245))

        var y = 64f

        // Header
        canvas.drawText("DAILY SALES REPORT", MARGIN, y + 42f, titlePaint)
        canvas.drawText(report.storeName, MARGIN, y + 82f, subtitlePaint)
        canvas.drawText(
            "${report.location}  •  ${formatHeaderDate(report.reportDate)}",
            MARGIN,
            y + 118f,
            subtitlePaint
        )
        y += 164f

        // Daily sales
        y = drawPanel(canvas, y, 190f, panelFill, panelStroke)
        canvas.drawText(
            dailySalesTitle(report.reportDate),
            MARGIN + 28f,
            y + 43f,
            sectionPaint
        )
        drawMetric(
            canvas, "American Tourister", money(report.atSales),
            96f, y + 82f, labelPaint, valuePaint
        )
        drawMetric(
            canvas, "Kamiliant", money(report.kamSales),
            430f, y + 82f, labelPaint, valuePaint
        )
        drawMetric(
            canvas, "TOTAL", money(report.totalSales),
            760f, y + 82f, labelPaint, totalPaint
        )
        y += 190f + GAP

        // Footfall / conversion
        y = drawPanel(canvas, y, 190f, panelFill, panelStroke)
        canvas.drawText(
            "FOOTFALL & CONVERSION",
            MARGIN + 28f,
            y + 43f,
            sectionPaint
        )
        drawMetric(
            canvas, "Walk-ins", report.footfall.toString(),
            96f, y + 84f, labelPaint, valuePaint
        )
        drawMetric(
            canvas, "Conversions", report.conversions.toString(),
            430f, y + 84f, labelPaint, valuePaint
        )
        drawMetric(
            canvas, "Conversion %", percent(report.conversionPercent),
            760f, y + 84f, labelPaint, valuePaint
        )
        y += 190f + GAP

        // Merchandise
        y = drawPanel(canvas, y, merchandiseHeight, panelFill, panelStroke)
        canvas.drawText(
            "MERCHANDISE SOLD",
            MARGIN + 28f,
            y + 43f,
            sectionPaint
        )

        val leftX = MARGIN + 28f
        val rightX = WIDTH / 2f + 18f
        val columnWidth = 450f
        val itemStartY = y + 80f

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
            itemStartY + 28f,
            columnWidth,
            bodyPaint,
            smallPaint
        )
        drawItems(
            canvas,
            report.merchandiseSold.kamiliant,
            rightX,
            itemStartY + 28f,
            columnWidth,
            bodyPaint,
            smallPaint
        )

        y += merchandiseHeight + GAP

        // Month to date
        val mtdHeight = 318f
        y = drawPanel(canvas, y, mtdHeight, panelFill, panelStroke)
        canvas.drawText(
            "MONTH TO DATE",
            MARGIN + 28f,
            y + 43f,
            sectionPaint
        )

        drawMetric(canvas, "American Tourister", money(report.monthToDate.atSales),
            96f, y + 84f, labelPaint, valuePaint)
        drawMetric(canvas, "Kamiliant", money(report.monthToDate.kamSales),
            560f, y + 84f, labelPaint, valuePaint)

        drawMetric(canvas, "Total", money(report.monthToDate.totalSales),
            96f, y + 142f, labelPaint, valuePaint)
        drawMetric(canvas, "Footfall", report.monthToDate.footfall.toString(),
            560f, y + 142f, labelPaint, valuePaint)

        drawMetric(canvas, "Conversions", report.monthToDate.conversions.toString(),
            96f, y + 200f, labelPaint, valuePaint)
        drawMetric(canvas, "Conversion %", percent(report.monthToDate.conversionPercent),
            560f, y + 200f, labelPaint, valuePaint)

        // AOV immediately below Conversion % in the MTD block.
        drawMetric(canvas, "AOV", money(report.monthToDate.aov),
            560f, y + 258f, labelPaint, valuePaint)

        y += mtdHeight + GAP

        canvas.drawLine(
            MARGIN,
            y,
            WIDTH - MARGIN,
            y,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(100, 255, 255, 255)
                strokeWidth = 2f
            }
        )
        canvas.drawText(
            "Sales Buddy  •  Store Sales Report",
            MARGIN,
            y + 34f,
            footerPaint
        )

        val directory = File(context.cacheDir, "report_cards").apply {
            mkdirs()
        }
        val safeDate = report.reportDate.replace(Regex("[^0-9-]"), "_")
        val output = File(directory, "Sales_Buddy_Store_Sales_Report_$safeDate.png")

        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Unable to encode the daily report image."
            }
        }
        bitmap.recycle()

        return output
    }

    private fun drawArtworkBackground(
        context: Context,
        canvas: Canvas,
        width: Int,
        height: Int
    ) {
        val resId = artworkIds.random()
        val bitmap = BitmapFactory.decodeResource(context.resources, resId)
            ?: throw IllegalStateException("Unable to load report artwork.")

        val sourceRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetRatio = width.toFloat() / height.toFloat()

        val sourceRect = if (sourceRatio > targetRatio) {
            val sourceWidth = (bitmap.height * targetRatio).toInt()
            val left = (bitmap.width - sourceWidth) / 2
            Rect(left, 0, left + sourceWidth, bitmap.height)
        } else {
            val sourceHeight = (bitmap.width / targetRatio).toInt()
            val top = (bitmap.height - sourceHeight) / 2
            Rect(0, top, bitmap.width, top + sourceHeight)
        }

        canvas.drawBitmap(
            bitmap,
            sourceRect,
            Rect(0, 0, width, height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        bitmap.recycle()
    }

    private fun drawOverlay(canvas: Canvas, width: Int, height: Int) {
        // Keep the artwork visibly present while ensuring white foreground text
        // remains readable over bright cyan/pink/orange areas.
        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Paint().apply {
                color = Color.argb(145, 0, 0, 8)
            }
        )
    }

    private fun drawPanel(
        canvas: Canvas,
        top: Float,
        height: Float,
        fill: Int,
        stroke: Int
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
            PANEL_RADIUS,
            PANEL_RADIUS,
            paint
        )
        paint.color = stroke
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRoundRect(
            MARGIN,
            top,
            WIDTH - MARGIN,
            top + height,
            PANEL_RADIUS,
            PANEL_RADIUS,
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
        canvas.drawText(value, x, y + 34f, valuePaint)
    }

    private fun drawItems(
        canvas: Canvas,
        items: List<ReportItem>,
        x: Float,
        startY: Float,
        width: Float,
        bodyPaint: Paint,
        smallPaint: Paint
    ) {
        if (items.isEmpty()) {
            canvas.drawText("No merchandise recorded.", x, startY + 12f, smallPaint)
            return
        }

        var y = startY
        items.forEachIndexed { index, item ->
            val title = listOf(item.model, item.size)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("  ")
            val detail = listOf(item.colour.trim(), money(item.sellingPrice))
                .filter { it.isNotEmpty() }
                .joinToString("  •  ")

            canvas.drawText(
                "${index + 1}. $title",
                x,
                y,
                bodyPaint
            )
            canvas.drawText(
                detail,
                x + 28f,
                y + 25f,
                smallPaint
            )
            y += 64f
        }
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
        moneyFormat.format(value)

    private fun percent(value: Double): String =
        String.format(Locale.US, "%.1f%%", value)

    private fun formatHeaderDate(value: String): String =
        runCatching {
            LocalDate.parse(value).format(
                DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
            )
        }.getOrElse { value }

    private fun dailySalesTitle(value: String): String =
        runCatching {
            val date = LocalDate.parse(value)
            if (date == LocalDate.now()) {
                "TODAY'S SALES"
            } else {
                "SALES FOR " + date.format(
                    DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH)
                ).uppercase(Locale.ENGLISH)
            }
        }.getOrElse {
            "SALES FOR $value"
        }
}
