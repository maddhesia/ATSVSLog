package com.sma.atsvslog.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.OkHttpClient
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
                headerName = config.apiKeyHeaderName
            )
        )
        .build()

    val api: AtSvsApi = Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AtSvsApi::class.java)
}
