package com.sma.atsvslog

import com.sma.atsvslog.network.AtSvsNetworkClient
import com.sma.atsvslog.network.NetworkConfig

object BetaNetwork {

    private const val BASE_URL =
        "https://script.google.com/macros/s/AKfycbzwHgVpEU4WXH2jT1PVnjVVQn2L_8JV8uHmMLnuGWHYvrP6CIvTUprWIYt1I4zr31z5/"

    /*
     * IMPORTANT:
     * Paste here the SAME Beta API key that you configured
     * in Apps Script's configureBeta() function.
     *
     * Do NOT send the key to me.
     */
    private const val API_KEY =
        "ATSVS-BETA1-Test-8VivekfK7xQ2mpMinty9vL4rtShikha6"

    val client: AtSvsNetworkClient by lazy {
        AtSvsNetworkClient(
            NetworkConfig(
                baseUrl = BASE_URL,
                apiKey = API_KEY
            )
        )
    }
}