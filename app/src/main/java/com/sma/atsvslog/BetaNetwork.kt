package com.sma.atsvslog

import com.sma.atsvslog.network.AtSvsNetworkClient
import com.sma.atsvslog.network.NetworkConfig

object BetaNetwork {

    private const val BASE_URL =
        "https://script.google.com/macros/s/AKfycbzwHgVpEU4WXH2jT1PVnjVVQn2L_8JV8uHmMLnuGWHYvrP6CIvTUprWIYt1I4zr31z5/"

    /*
     * The Beta API key is deliberately NOT stored in source control.
     *
     * Gradle obtains it from:
     *   ATSVS_BETA_API_KEY
     *
     * via the BuildConfig field generated in app/build.gradle.kts.
     *
     * Do not hardcode the key here and do not send it to ChatGPT.
     */
    private val API_KEY: String =
        BuildConfig.BETA_API_KEY.trim().also { key ->
            require(key.isNotEmpty()) {
                "ATSVS_BETA_API_KEY is not configured. " +
                    "Add it to %USERPROFILE%\\.gradle\\gradle.properties " +
                    "or the ATSVS_BETA_API_KEY environment variable before running the app."
            }
        }

    val client: AtSvsNetworkClient by lazy {
        AtSvsNetworkClient(
            NetworkConfig(
                baseUrl = BASE_URL,
                apiKey = API_KEY
            )
        )
    }
}
