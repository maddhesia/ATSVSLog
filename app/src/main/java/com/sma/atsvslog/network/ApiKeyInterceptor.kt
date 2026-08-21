package com.sma.atsvslog.network

import okhttp3.Interceptor
import okhttp3.Response

class ApiKeyInterceptor(
    private val apiKey: String?,
    private val headerName: String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (apiKey.isNullOrBlank()) {
            return chain.proceed(request)
        }

        val authenticated = request.newBuilder()
            .header(headerName, apiKey)
            .build()

        return chain.proceed(authenticated)
    }
}
