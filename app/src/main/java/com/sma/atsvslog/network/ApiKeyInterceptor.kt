package com.sma.atsvslog.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Apps Script web-app compatibility layer.
 *
 * Google Apps Script web-app doGet/doPost event objects expose query/path/body
 * parameters, but not arbitrary incoming HTTP request headers. Therefore the
 * Beta API key is transported as a query parameter over HTTPS rather than as
 * X-API-Key. See the Milestone 6 contract notes.
 */
class ApiKeyInterceptor(
    private val apiKey: String?,
    private val parameterName: String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (apiKey.isNullOrBlank()) {
            return chain.proceed(request)
        }

        val url: HttpUrl = request.url.newBuilder()
            .setQueryParameter(parameterName, apiKey)
            .build()

        return chain.proceed(
            request.newBuilder()
                .url(url)
                .build()
        )
    }
}
