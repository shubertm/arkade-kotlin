package com.arkade.network.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Creates a platform HTTP client with [config]. Base URL configuration is supplied by [config];
 * [baseUrl] identifies the intended API root. Native client creation is not yet implemented.
 */
internal expect fun httpClient(
    baseUrl: String,
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient

/**
 * Creates an HTTP client for [baseUrl] with JSON content negotiation that ignores unknown fields.
 *
 * Appends a trailing slash when absent so relative request paths resolve beneath the API root.
 * Uses CIO on Android and JVM. The caller is responsible for closing the returned client.
 *
 * @param baseUrl Absolute API root URL, including any path prefix such as `/api`.
 * @throws NotImplementedError On native platforms, where client creation is not implemented.
 */
fun getHttpClient(baseUrl: String): HttpClient =
    httpClient(baseUrl) {
        defaultRequest {
            val baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            url(baseUrl)
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                },
            )
        }
    }
