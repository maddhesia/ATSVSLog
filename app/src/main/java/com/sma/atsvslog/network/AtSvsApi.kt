package com.sma.atsvslog.network

import com.google.gson.JsonObject
import com.sma.atsvslog.network.dto.ApiRequest
import com.sma.atsvslog.network.dto.ApiResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Milestone 6 Fix 1 — anonymous Apps Script routing correction. See
 * ADR-M6-002.
 *
 * Google Apps Script Web Apps require a signed-in Google session for ANY
 * request that carries additional URL path segments after /exec (exposed
 * to the script as e.pathInfo) — regardless of the deployment's
 * "Execute as: Me" + "Who has access: Anyone" configuration. Only the
 * bare deployment URL — exactly ".../exec", no trailing slash, query
 * parameters only — supports true anonymous access. ".../exec/" (with a
 * trailing slash) is treated by Google's front end as an unrecognized
 * route and 404s before the script ever runs; it is NOT the same as the
 * bare URL, even though both look "path-segment free" at a glance.
 *
 * The configured base URL (see BetaNetwork.kt / NetworkConfig) is
 * therefore the deployment's *parent* directory, ending just before
 * "exec/" — NOT the /exec URL itself. "exec" below is a literal, static
 * relative path, so the resolved request is exactly
 * ".../exec?op=...&apiKey=...": no trailing slash after "exec", and
 * nothing appended after it. GET operations are distinguished by the
 * `op` query parameter; POST operations are distinguished by the JSON
 * envelope's `action` field — never by the URL path.
 */
interface AtSvsApi {

    @GET("exec")
    suspend fun health(
        @Query("op") op: String = "health"
    ): Response<ApiResponse<JsonObject>>

    @POST("exec")
    suspend fun sync(
        @Body request: ApiRequest<JsonObject>
    ): Response<ApiResponse<JsonObject>>

    @GET("exec")
    suspend fun masters(
        @Query("op") op: String = "masters"
    ): Response<ApiResponse<JsonObject>>

    @POST("exec")
    suspend fun report(
        @Body request: ApiRequest<JsonObject>
    ): Response<ApiResponse<JsonObject>>
}
