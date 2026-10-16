package com.arkade.network.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO

internal actual fun httpClient(
    baseUrl: String,
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(CIO, config)
