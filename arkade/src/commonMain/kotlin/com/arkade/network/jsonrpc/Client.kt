package com.arkade.network.jsonrpc

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray

suspend fun <T> HttpClient.sendRpc(
    method: String,
    params: JsonArray = JsonArray(emptyList()),
): T? {
    val response =
        post {
            contentType(ContentType.Application.Json)
            val requestBody = RpcRequest(method = method, params = params)
            setBody(requestBody)
        }
    if (!response.status.isSuccess()) throw Exception("RPC failed: ${response.status}")

    val rpcResponse: RpcResponse<T> = response.body()

    if (rpcResponse.error != null) throw RpcException("${rpcResponse.error}")

    return rpcResponse.result
}
