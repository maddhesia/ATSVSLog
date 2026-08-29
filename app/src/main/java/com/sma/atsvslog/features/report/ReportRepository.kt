package com.sma.atsvslog.features.report

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sma.atsvslog.network.ApiRequestFactory
import com.sma.atsvslog.network.dto.ACTION_REPORT
import com.sma.atsvslog.network.dto.ApiResponse
import com.sma.atsvslog.network.AtSvsApi
import retrofit2.Response

class ReportRepository(
    private val api: AtSvsApi,
    private val gson: Gson = Gson()
) {
    suspend fun fetchReport(date: String): DailyReport {
        require(Regex("""\d{4}-\d{2}-\d{2}""").matches(date)) {
            "Invalid report date: $date"
        }

        val payload = JsonObject().apply {
            addProperty("reportDate", date)
        }

        val request = ApiRequestFactory.create(
            action = ACTION_REPORT,
            payload = payload
        )

        val response: Response<ApiResponse<JsonObject>> =
            api.report(request)

        if (!response.isSuccessful) {
            throw IllegalStateException(
                "REPORT HTTP ${response.code()}"
            )
        }

        val body = response.body()
            ?: throw IllegalStateException("REPORT empty response")

        if (!body.success) {
            throw IllegalStateException(
                body.statusCode.ifBlank { "REPORT_FAILED" }
            )
        }

        val reportJson = body.payload
            ?.getAsJsonObject("report")
            ?: throw IllegalStateException("REPORT payload missing")

        return gson.fromJson(
            reportJson,
            DailyReport::class.java
        )
    }
}
