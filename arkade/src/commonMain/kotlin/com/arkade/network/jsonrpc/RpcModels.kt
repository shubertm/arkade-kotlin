package com.arkade.network.jsonrpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class RpcRequest(
    @SerialName("jsonrpc")
    val jsonRpc: String = "1.0",
    val id: String = "Arkade",
    val method: String,
    val params: JsonArray = JsonArray(emptyList()),
)

@Serializable
data class RpcResponse<T>(
    val result: T?,
    val error: RpcError?,
    val id: String,
)

@Serializable
data class RpcError(
    val code: Int,
    val message: String,
)
