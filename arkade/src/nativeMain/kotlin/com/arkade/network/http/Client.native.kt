package com.arkade.network.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

internal actual fun httpClient(
    baseUrl: String,
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    TODO("Not yet implemented")
}
