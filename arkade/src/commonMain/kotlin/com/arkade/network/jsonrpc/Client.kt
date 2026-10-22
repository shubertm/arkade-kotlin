package com.arkade.network.jsonrpc

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.CancellationException
import kotlinx.serialization.json.JsonArray

suspend inline fun <reified T> HttpClient.sendRpc(
    method: String,
    params: JsonArray = JsonArray(emptyList()),
): T? {
    val response =
        post {
            expectSuccess = false
            contentType(ContentType.Application.Json)
            val requestBody = RpcRequest(method = method, params = params)
            setBody(requestBody)
        }

    if (!response.status.isSuccess()) {
        val rpcError: RpcError? =
            runCatching { response.body<RpcResponse<T>>().error }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                null
            }
        throw if (rpcError != null) {
            RpcException("$rpcError")
        } else {
            Exception("RPC failed: ${response.status}")
        }
    }

    val rpcResponse: RpcResponse<T> = response.body()

    if (rpcResponse.error != null) throw RpcException("${rpcResponse.error}")

    return rpcResponse.result
}
