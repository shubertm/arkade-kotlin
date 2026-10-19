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
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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

/**
 * Bitcoin blockchain provider backed by NBXplorer's Bitcoin HTTP API and JSON-RPC proxy.
 *
 * Requests use absolute paths under `/v1/cryptos/BTC`, replacing any path prefix in the base URL.
 * The server must expose the RPC methods used by this provider. Transport and decoding failures
 * propagate to the caller; individual operations document HTTP errors and fallback results.
 */
class NBXplorerBlockchain private constructor() : Blockchain {
    /**
     * Creates a provider using an existing [httpClient], which this provider does not close.
     *
     * Configure the NBXplorer server URL, any required authentication, and JSON content negotiation
     * for request serialization. To preserve status-based fallbacks, configure the client to return
     * non-success responses without throwing (Ktor's default `expectSuccess = false`).
     */
    constructor(httpClient: HttpClient) : this() {
        this.httpClient = httpClient
    }

    /**
     * Creates a provider using [getHttpClient] for the NBXplorer server at [baseUrl].
     *
     * Use the [HttpClient] constructor when custom authentication or client configuration is needed.
     *
     * @throws NotImplementedError On native platforms, where the HTTP client factory is not implemented.
     */
    constructor(baseUrl: String) : this() {
        httpClient = getHttpClient(baseUrl)
    }

    private lateinit var httpClient: HttpClient

    private val rpcRequestSemaphore = Semaphore(10)

    /**
     * Calls `getblockchaininfo` and returns its `time` in Unix seconds and `blocks` as the tip height.
     * Throws if the HTTP response is unsuccessful or either required result field is missing.
     */
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

    /**
     * Resolves [blockHeight] with `getblockhash`, then requests a verbose `getblockheader` result.
     * Returns `mediantime` in Unix seconds, or `null` when the hash result or median time is absent.
     * Unsuccessful HTTP responses and malformed results throw.
     */
    override suspend fun getMedianTimePast(blockHeight: Long): Long? {
        val medianTime =
            runCatching {
                rpcGetBlockHeader(blockHeight)
                    ?.jsonObject
                    ?.get("mediantime")
                    ?.jsonPrimitive
                    ?.long
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                null
            }

        return medianTime
    }

    /**
     * Returns confirmed and unconfirmed outputs for [address], with values in satoshis.
     *
     * Exclude outpoints marked spent in either section of the response.
     * Unsuccessful HTTP responses, missing sections
     * or spent-outpoint lists, and outputs missing a transaction hash, index, or value throw.
     */
    override suspend fun getUtxos(address: String): List<BoardingUtxo> {
        val response =
            httpClient.get("/v1/cryptos/BTC/addresses/$address/utxos") {
                contentType(ContentType.Application.Json)
            }
        if (!response.status.isSuccess()) {
            throw Exception("Failed to get utxos: ${response.status}")
        }
        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val currentHeight = result["currentHeight"]?.jsonPrimitive?.long ?: throw Exception("Failed to parse utxos")
        val confirmed = result["confirmed"]?.jsonObject
        val unConfirmed = result["unconfirmed"]?.jsonObject

        if (confirmed == null || unConfirmed == null) throw Exception("Failed to parse utxos")

        val confirmedSpentOutpoints = confirmed.jsonObject["spentOutpoints"]?.jsonArray?.map { it.jsonPrimitive.content }
        val unConfirmedSpentOutpoints = unConfirmed.jsonObject["spentOutpoints"]?.jsonArray?.map { it.jsonPrimitive.content }

        if (confirmedSpentOutpoints == null || unConfirmedSpentOutpoints == null) {
            throw Exception("Failed to parse utxos")
        }

        val spentOutpoints = confirmedSpentOutpoints.plus(unConfirmedSpentOutpoints)

        val confirmedUtxos = parseUtxosFromJson(confirmed, currentHeight, true)
        val unConfirmedUtxos = parseUtxosFromJson(unConfirmed, currentHeight, false)

        val utxos = confirmedUtxos.plus(unConfirmedUtxos)

        return utxos.filter { utxo ->
            spentOutpoints.none { spentOutpoint ->
                spentOutpoint == "${utxo.txId}-${utxo.vOut}"
            }
        }
    }

    /**
     * Posts [tx] as a JSON object containing its hex to NBXplorer's transactions endpoint.
     * Returns the response's `success` flag, or `false` when that flag is absent.
     * An unsuccessful HTTP response throws.
     */
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

    /**
     * Submits [parent] followed by [child] through `submitpackage`, with fee-rate and burn limits
     * set to `0` and `21_000_000`, respectively.
     *
     * HTTP 400, 404, 405, or 501 triggers sequential broadcasting via `sendrawtransaction` for the
     * parent and [broadcast] for the child. That fallback returns `false` if the parent's HTTP status
     * is unsuccessful; otherwise it returns `true` even if the child returns `false`. Exceptions
     * from either request still propagate.
     *
     * Other unsuccessful HTTP responses return `false`. A successful HTTP response returns `true`
     * only when the RPC error is absent or JSON null and the result's `package_msg` is `success`.
     */
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

    /**
     * Fetches [txId] and treats a positive confirmation count as confirmed.
     *
     * A missing confirmation count defaults to zero; a nonpositive count is treated as being in
     * the mempool. The response's height is retained when present, regardless of confirmation count.
     * Unsuccessful HTTP responses throw, including HTTP 404 for an unknown transaction.
     */
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

    /**
     * Fetches NBXplorer's fee rate in satoshis per virtual byte for [confirmTarget] blocks and
     * rounds it up. Uses `2f` when the `feeRate` field is absent.
     * Unsuccessful HTTP responses and malformed fee rates throw.
     */
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

    /**
     * Calls `getrawtransaction` for [txId] and decodes the returned transaction hex.
     * Returns `null` for HTTP 404 or an absent RPC result; other unsuccessful HTTP responses,
     * malformed results, and invalid transaction hex throw. The decoded transaction ID is
     * checked against [txId].
     */
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

        val tx = Transaction.read(hex)

        if (tx.txid != txId) return null

        return tx
    }

    /**
     * Broadcasts the parent via RPC, then the child via [broadcast]. A successful parent HTTP status
     * counts as success even if the child returns `false`; exceptions still propagate.
     */
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

    /**
     * Calls `sendrawtransaction` for [tx] with fee-rate and burn limits of `0` and `21_000_000`.
     * Returns whether the HTTP status is successful without checking the RPC response body.
     */
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

    private suspend fun rpcGetBlockHeader(height: Long): JsonElement? {
        val response =
            httpClient.post("/v1/cryptos/BTC/rpc") {
                contentType(ContentType.Application.Json)
                setBody(
                    JSONRPCPayload(
                        method = "getblockhash",
                        params = listOf(JsonPrimitive(height)),
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

        return Json.parseToJsonElement(blockResponse.bodyAsText()).jsonObject["result"]
    }

    /**
     * Parses the `utxOs` list from a confirmed or unconfirmed response section.
     * Requires each output's transaction hash, index, and value.
     */
    private suspend fun parseUtxosFromJson(
        utxosJson: JsonObject,
        currentHeight: Long,
        confirmed: Boolean,
    ): List<BoardingUtxo> {
        val blockTimeByHeight = utxoBlockTimeByHeight(utxosJson, currentHeight)

        return utxosJson["utxOs"]?.jsonArray?.map { utxo ->
            val txId =
                utxo.jsonObject["transactionHash"]?.jsonPrimitive?.content
                    ?: throw Exception("Failed to parse utxos: missing txId")
            val index =
                utxo.jsonObject["index"]?.jsonPrimitive?.int
                    ?: throw Exception("Failed to parse utxos: missing tx output index")
            val value =
                utxo.jsonObject["value"]?.jsonPrimitive?.long
                    ?: throw Exception("Failed to parse utxos: missing tx output value")
            val confirmations = utxo.jsonObject["confirmations"]?.jsonPrimitive?.long ?: 0
            val blockHeight = if (confirmations > 0) (currentHeight - confirmations) + 1 else null
            val blockTime = if (blockHeight != null) blockTimeByHeight[blockHeight] else null

            BoardingUtxo(
                TxId(txId),
                index,
                value,
                confirmed,
                blockHeight,
                blockTime,
            )
        } ?: throw Exception("Failed to parse utxos")
    }

    private suspend fun utxoBlockTimeByHeight(
        utxoJson: JsonObject,
        currentHeight: Long,
    ): Map<Long, Long?> {
        val uniqueBlockHeights = mutableSetOf<Long>()
        utxoJson["utxOs"]?.jsonArray?.forEach { utxo ->
            val confirmations = utxo.jsonObject["confirmations"]?.jsonPrimitive?.long ?: return@forEach
            val blockHeight = if (confirmations > 0) (currentHeight - confirmations) + 1 else return@forEach
            uniqueBlockHeights.add(blockHeight)
        }

        return withContext(Dispatchers.IO) {
            uniqueBlockHeights
                .map { height ->
                    async {
                        rpcRequestSemaphore.withPermit {
                            val time =
                                runCatching {
                                    rpcGetBlockHeader(height)
                                        ?.jsonObject
                                        ?.get("time")
                                        ?.jsonPrimitive
                                        ?.long
                                }.getOrElse { throwable ->
                                    if (throwable is CancellationException) throw throwable
                                    null
                                }
                            height to time
                        }
                    }
                }.awaitAll()
                .toMap()
        }
    }

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
