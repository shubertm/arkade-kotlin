package com.arkade.network.jsonrpc

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray

suspend inline fun <reified T> HttpClient.sendRpc(
    method: String,
    params: JsonArray = JsonArray(emptyList()),
): T? {
    val response =
        post {
            contentType(ContentType.Application.Json)
            val requestBody = RpcRequest(method = method, params = params)
            setBody(requestBody)
        }

    val rpcResponse: RpcResponse<T> = response.body()

    if (!response.status.isSuccess()) {
        throw if (rpcResponse.error != null) {
            RpcException("${rpcResponse.error}")
        } else {
            Exception("RPC failed: ${response.status}")
        }
    }

    if (rpcResponse.error != null) throw RpcException("${rpcResponse.error}")

    return rpcResponse.result
}
