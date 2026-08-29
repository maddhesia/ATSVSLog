package com.sma.atsvslog.features.report

data class ReportRequestPayload(
    val reportDate: String
)

data class DailyReport(
    val storeName: String,
    val location: String,
    val reportDate: String,
    val atSales: Long,
    val kamSales: Long,
    val totalSales: Long,
    val footfall: Int,
    val conversions: Int,
    val conversionPercent: Double,
    val merchandiseSold: MerchandiseSold,
    val monthToDate: MonthToDate,
    val insights: List<String>
)

data class MerchandiseSold(
    val americanTourister: List<ReportItem>,
    val kamiliant: List<ReportItem>
)

data class ReportItem(
    val itemUuid: String,
    val type: String,
    val brand: String,
    val model: String,
    val size: String,
    val colour: String,
    val sellingPrice: Long
)

data class MonthToDate(
    val atSales: Long,
    val kamSales: Long,
    val totalSales: Long,
    val footfall: Int,
    val conversions: Int
)
