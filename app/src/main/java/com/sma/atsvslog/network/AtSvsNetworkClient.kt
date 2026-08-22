package com.sma.atsvslog.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class AtSvsNetworkClient(
    config: NetworkConfig
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
        .addInterceptor(
            ApiKeyInterceptor(
                apiKey = config.apiKey,
                parameterName = config.apiKeyParameterName
            )
        )
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())

            val responseBody = response.body
            val responseText = responseBody?.string()

            Log.d(
                "ATSVS_HTTP",
                """
    HTTP ${response.code}
    FINAL URL: ${response.request.url}
    PREVIOUS RESPONSE: ${response.priorResponse?.code}
    PREVIOUS LOCATION: ${response.priorResponse?.headers?.get("Location")}
    BODY:
    $responseText
    """.trimIndent()
            )

            response.newBuilder()
                .body(
                    responseText?.toResponseBody(
                        responseBody?.contentType()
                    )
                )
                .build()
        }
        .build()

    val api: AtSvsApi = Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AtSvsApi::class.java)
}