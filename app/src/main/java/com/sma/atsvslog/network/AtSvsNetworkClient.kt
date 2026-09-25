package com.sma.atsvslog.network

import android.util.Log
import com.sma.atsvslog.BuildConfig
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
            val request = chain.request()
            val response = chain.proceed(request)

            val responseBody = response.body
            val responseText = responseBody?.string()

            /*
             * M16 release-hardening rule:
             * never write the API key, full query string, redirect Location,
             * or response body to Logcat.
             *
             * Even DEBUG builds keep the log intentionally redacted. This
             * preserves the frozen "Never log secrets" rule.
             */
            if (BuildConfig.DEBUG) {
                Log.d(
                    "ATSVS_HTTP",
                    "HTTP ${response.code} ${request.method} ${request.url.encodedPath}"
                )
            }

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
