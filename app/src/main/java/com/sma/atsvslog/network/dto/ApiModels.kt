package com.sma.atsvslog.network.dto

import com.google.gson.JsonObject

data class ApiRequest<T>(
    val apiVersion: Int = API_VERSION,
    val requestId: String,
    val action: String,
    val timestamp: String,
    val payload: T
)

data class ApiResponse<T>(
    val success: Boolean,
    val statusCode: String,
    val message: String?,
    val serverTime: String?,
    val apiVersion: Int,
    val payload: T? = null
)

data class NetworkHealth(
    val payload: JsonObject? = null
)

const val API_VERSION = 1
const val ACTION_SYNC = "SYNC"
const val ACTION_HEALTH = "HEALTH"
const val ACTION_MASTERS = "MASTERS"
const val ACTION_REPORT = "REPORT"
