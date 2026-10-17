package com.arkade.network.grpc

import com.squareup.wire.GrpcClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import kotlin.time.Duration.Companion.seconds

/**
 * Creates a configured [GrpcClient] for the given server URL.
 *
 * The returned client is backed by an OkHttpClient that supports HTTP/2 and HTTP/1.1 and
 * applies a 60-second call timeout, 30-second read timeout, and 30-second connect timeout.
 *
 * @param baseUrl The base URL of the gRPC server (scheme + host, and optional
 * port/path as required).
 * @return A [GrpcClient] instance configured to use the specified base URL and the
 * described HTTP settings.
 */
actual fun gRPCClient(baseUrl: String): GrpcClient =
    GrpcClient
        .Builder()
        .client(
            OkHttpClient
                .Builder()
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .callTimeout(60.seconds)
                .readTimeout(30.seconds)
                .connectTimeout(30.seconds)
                .build(),
        ).baseUrl(baseUrl)
        .build()
