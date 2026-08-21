package com.sma.atsvslog.network

data class NetworkConfig(
    val baseUrl: String,
    val apiKey: String? = null,
    val apiKeyHeaderName: String = DEFAULT_API_KEY_HEADER,
    val connectTimeoutSeconds: Long = 10,
    val readTimeoutSeconds: Long = 20,
    val writeTimeoutSeconds: Long = 20
) {
    init {
        require(baseUrl.endsWith("/")) {
            "Network baseUrl must end with '/': $baseUrl"
        }
        require(connectTimeoutSeconds > 0)
        require(readTimeoutSeconds > 0)
        require(writeTimeoutSeconds > 0)
        if (apiKey != null) {
            require(apiKeyHeaderName.isNotBlank())
        }
    }
}

const val DEFAULT_API_KEY_HEADER = "X-API-Key"
