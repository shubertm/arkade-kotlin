package com.arkade.network.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO

/** Creates a CIO client using [config]; the configuration must supply the base URL. */
internal actual fun httpClient(
    baseUrl: String,
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(CIO, config)
