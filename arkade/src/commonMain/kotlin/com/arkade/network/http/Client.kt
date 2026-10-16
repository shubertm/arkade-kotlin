package com.arkade.network.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

internal expect fun httpClient(
    baseUrl: String,
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient

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
