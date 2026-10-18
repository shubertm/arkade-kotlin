package com.arkade.core.bitcoin.blockchain

import com.arkade.network.http.getHttpClient
import com.arkade.utils.Log
import com.arkade.utils.debug
import com.arkade.utils.warning
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.ceil

class NBXplorerBlockchain private constructor() : Blockchain {
    constructor(httpClient: HttpClient) : this() {
        this.httpClient = httpClient
    }

    constructor(baseUrl: String) : this() {
        httpClient = getHttpClient(baseUrl)
    }

    private lateinit var httpClient: HttpClient

    override suspend fun getChainTime(): ChainTime {
        val response =
            httpClient.post("/v1/cryptos/BTC/rpc") {
                contentType(ContentType.Application.Json)
                setBody(JSONRPCPayload(method = "getblockchaininfo", params = emptyList()))
            }

        if (!response.status.isSuccess()) {
            throw Exception("Failed to get blockchain info: ${response.status}")
        }

        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]
        val time =
            result
                ?.jsonObject
                ?.get("time")
                ?.jsonPrimitive
                ?.long
        val height =
            result
                ?.jsonObject
                ?.get("blocks")
                ?.jsonPrimitive
                ?.long

        if (time == null || height == null) {
            throw Exception("Failed to parse blockchain info")
        }

        return ChainTime(time, height)
    }

    override suspend fun getMedianTimePast(blockHeight: Long): Long? {
        val response =
            httpClient.post("/v1/cryptos/BTC/rpc") {
                contentType(ContentType.Application.Json)
                setBody(
                    JSONRPCPayload(
                        method = "getblockhash",
                        params = listOf(JsonPrimitive(blockHeight)),
                    ),
                )
            }

        if (!response.status.isSuccess()) {
            throw Exception("Failed to get block hash: ${response.status}")
        }

        val blockHash =
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]?.jsonPrimitive
                ?: return null

        val blockResponse =
            httpClient.post("/v1/cryptos/BTC/rpc") {
                contentType(ContentType.Application.Json)
                setBody(
                    JSONRPCPayload(
                        method = "getblockheader",
                        params = listOf(blockHash, JsonPrimitive(true)),
                    ),
                )
            }

        if (!blockResponse.status.isSuccess()) {
            throw Exception("Failed to get block header: ${blockResponse.status}")
        }

        val block = Json.parseToJsonElement(blockResponse.bodyAsText()).jsonObject["result"]
        val time =
            block
                ?.jsonObject
                ?.get("mediantime")
                ?.jsonPrimitive
                ?.long

        return time
    }

    override suspend fun getUtxos(address: String): List<BoardingUtxo> {
        val response =
            httpClient.get("/v1/cryptos/BTC/addresses/$address/utxos") {
                contentType(ContentType.Application.Json)
            }
        if (!response.status.isSuccess()) {
            throw Exception("Failed to get utxos: ${response.status}")
        }
        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val confirmed = result["confirmed"]?.jsonObject
        val unConfirmed = result["unconfirmed"]?.jsonObject

        if (confirmed == null || unConfirmed == null) throw Exception("Failed to parse utxos")

        val confirmedSpentOutpoints = confirmed.jsonObject["spentOutpoints"]?.jsonArray?.map { it.jsonPrimitive.content }
        val unConfirmedSpentOutpoints = unConfirmed.jsonObject["spentOutpoints"]?.jsonArray?.map { it.jsonPrimitive.content }

        if (confirmedSpentOutpoints == null || unConfirmedSpentOutpoints == null) {
            throw Exception("Failed to parse utxos")
        }

        val spentOutpoints = confirmedSpentOutpoints.plus(unConfirmedSpentOutpoints)

        val confirmedUtxos = parseUtxosFromJson(confirmed, true)
        val unConfirmedUtxos = parseUtxosFromJson(unConfirmed, false)

        val utxos = confirmedUtxos.plus(unConfirmedUtxos)

        return utxos.filter { utxo ->
            spentOutpoints.none { spentOutpoint ->
                spentOutpoint == "${utxo.txId}-${utxo.vOut}"
            }
        }
    }

    override suspend fun broadcast(tx: Transaction): Boolean {
        val response =
            httpClient.post("/v1/cryptos/BTC/transactions") {
                contentType(ContentType.Application.Json)
                setBody(TxPayload(tx.toString()))
            }

        if (!response.status.isSuccess()) {
            throw Exception("Failed to broadcast transaction: ${response.status}")
        }

        val isSuccessful =
            Json
                .parseToJsonElement(response.bodyAsText())
                .jsonObject["success"]
                ?.jsonPrimitive
                ?.boolean ?: false

        return isSuccessful
    }

    override suspend fun broadcastPackage(
        parent: Transaction,
        child: Transaction,
    ): Boolean {
        val response =
            httpClient.post("/v1/cryptos/BTC/rpc") {
                contentType(ContentType.Application.Json)
                setBody(
                    JSONRPCPayload(
                        method = "submitpackage",
                        params =
                            listOf(
                                JsonArray(
                                    listOf(
                                        JsonPrimitive(parent.toString()),
                                        JsonPrimitive(child.toString()),
                                    ),
                                ),
                                JsonPrimitive(0),
                                JsonPrimitive(21_000_000),
                            ),
                    ),
                )
            }

        if (
            response.status == HttpStatusCode.NotImplemented ||
            response.status == HttpStatusCode.BadRequest ||
            response.status == HttpStatusCode.MethodNotAllowed ||
            response.status == HttpStatusCode.NotFound
        ) {
            return broadcastPackageFallback(parent, child)
        }

        if (!response.status.isSuccess()) {
            Log.warning(LOG_TAG, "submitpackage failed: ${response.status.description}")
            return false
        }

        val responseBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = responseBody["result"] as? JsonObject
        val packageMsg = result?.get("package_msg")?.jsonPrimitive?.content
        val error = responseBody["error"]

        if ((error != null && error != JsonNull) || packageMsg != "success") {
            Log.warning(LOG_TAG, "Transaction package was rejected")
            return false
        }

        return true
    }

    override suspend fun getTxStatus(txId: TxId): Blockchain.TxStatus {
        val response =
            httpClient.get("/v1/cryptos/BTC/transactions/$txId") {
                contentType(ContentType.Application.Json)
            }

        if (!response.status.isSuccess()) {
            throw Exception("Failed to get transaction status: ${response.status}")
        }

        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val confirmations = result["confirmations"]?.jsonPrimitive?.long ?: 0L
        val height = result["height"]?.jsonPrimitive?.int

        return Blockchain.TxStatus(confirmations > 0, height, confirmations <= 0L)
    }

    override suspend fun estimateFeeRate(confirmTarget: Int): Float {
        val response =
            httpClient.get("/v1/cryptos/BTC/fees/$confirmTarget") {
                contentType(ContentType.Application.Json)
            }

        if (!response.status.isSuccess()) throw Exception("Failed to estimate fee rate")

        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val feeRate = result["feeRate"]?.jsonPrimitive?.float ?: 2f

        return ceil(feeRate)
    }

    override suspend fun getRawTx(txId: TxId): Transaction? {
        val response =
            httpClient.post("/v1/cryptos/BTC/rpc") {
                contentType(ContentType.Application.Json)
                setBody(
                    JSONRPCPayload(
                        method = "getrawtransaction",
                        params = listOf(JsonPrimitive(txId.toString())),
                    ),
                )
            }

        if (response.status == HttpStatusCode.NotFound) return null

        if (!response.status.isSuccess()) {
            throw Exception("Failed to get raw transaction: ${response.status}")
        }

        val hex =
            Json
                .parseToJsonElement(response.bodyAsText())
                .jsonObject["result"]
                ?.jsonPrimitive
                ?.content ?: return null

        return Transaction.read(hex)
    }

    private suspend fun broadcastPackageFallback(
        parent: Transaction,
        child: Transaction,
    ): Boolean {
        val isParentSuccessful = rpcBroadcast(parent)
        if (!isParentSuccessful) return false
        val isChildSuccessful = broadcast(child)
        if (!isChildSuccessful) {
            Log.debug(LOG_TAG, "Sequantial fallback: child CPFP broadcast failed, but parent was accepted")
        }
        return true
    }

    private suspend fun rpcBroadcast(tx: Transaction): Boolean {
        val response =
            httpClient.post("/v1/cryptos/BTC/rpc") {
                contentType(ContentType.Application.Json)
                setBody(
                    JSONRPCPayload(
                        method = "sendrawtransaction",
                        params =
                            listOf(
                                JsonPrimitive(tx.toString()),
                                JsonPrimitive(0),
                                JsonPrimitive(21_000_000),
                            ),
                    ),
                )
            }

        if (!response.status.isSuccess()) {
            Log.warning(LOG_TAG, "sendrawtransaction failed: ${response.status.description}")
            return false
        }
        return true
    }

    private fun parseUtxosFromJson(
        utxosJson: JsonObject,
        confirmed: Boolean,
    ): List<BoardingUtxo> =
        utxosJson["utxOs"]?.jsonArray?.map { utxo ->
            val txId = utxo.jsonObject["transactionHash"]?.jsonPrimitive?.content ?: throw Exception("Failed to parse utxos: missing txId")
            val index = utxo.jsonObject["index"]?.jsonPrimitive?.int ?: throw Exception("Failed to parse utxos: missing tx output index")
            val value = utxo.jsonObject["value"]?.jsonPrimitive?.long ?: throw Exception("Failed to parse utxos: missing tx output value")
            BoardingUtxo(
                TxId(txId),
                index,
                value,
                confirmed,
            )
        } ?: throw Exception("Failed to parse utxos")

    companion object {
        private const val LOG_TAG = "NBXplorer"
    }

    @Serializable
    private data class JSONRPCPayload(
        @SerialName("jsonrpc")
        val jsonRPC: String = "1.0",
        val method: String,
        val params: List<JsonElement>,
    )

    @Serializable
    private data class TxPayload(
        val hex: String,
    )
}
