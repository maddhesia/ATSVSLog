package com.sma.atsvslog.network

import com.google.gson.JsonObject
import com.sma.atsvslog.network.dto.ApiRequest
import com.sma.atsvslog.network.dto.ApiResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * The server paths are deliberately supplied through @Url.
 *
 * The canonical TDD defines the API operations, but the deployed Apps Script
 * path is environment-specific. Keeping paths outside this interface prevents
 * us from inventing or freezing a URL before the backend is provisioned.
 */
interface AtSvsApi {

    @GET
    suspend fun health(
        @Url path: String
    ): Response<ApiResponse<JsonObject>>

    @POST
    suspend fun sync(
        @Url path: String,
        @Body request: ApiRequest<JsonObject>
    ): Response<ApiResponse<JsonObject>>

    @GET
    suspend fun masters(
        @Url path: String
    ): Response<ApiResponse<JsonObject>>

    @POST
    suspend fun report(
        @Url path: String,
        @Body request: ApiRequest<JsonObject>
    ): Response<ApiResponse<JsonObject>>
}
