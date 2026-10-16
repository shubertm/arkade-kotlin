package com.arkade.core.bitcoin.blockchain

import com.arkade.utils.Log
import com.arkade.utils.debug
import com.arkade.utils.warning
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil

class EsploraBlockchain private constructor() : Blockchain {
    constructor(baseUrl: String) : this() {
        this.httpClient =
            HttpClient(CIO) {
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
    }

    constructor(httpClient: HttpClient) : this() {
        this.httpClient = httpClient
    }

    private lateinit var httpClient: HttpClient

    override suspend fun getChainTime(): ChainTime =
        runCatching {
            val tipHashResponse = httpClient.get("blocks/tip/hash")
            if (!tipHashResponse.status.isSuccess()) {
                throw Exception("Failed to fetch tip hash")
            }

            val tipHash = tipHashResponse.bodyAsText()
            val blockResponse =
                httpClient.get("block/$tipHash") {
                    contentType(ContentType.Application.Json)
                }
            if (!blockResponse.status.isSuccess()) {
                throw Exception("Failed to fetch block $tipHash")
            }

            val block = blockResponse.body<BlockResponse>()
            return ChainTime(block.medianTime, block.height)
        }.getOrElse { throwable -> throw throwable }

    override suspend fun getMedianTimePast(blockHeight: Long): Long? =
        runCatching {
            val hashResponse = httpClient.get("block-height/$blockHeight")
            if (!hashResponse.status.isSuccess()) return@runCatching null

            val blockHash = hashResponse.bodyAsText()
            if (blockHash.isEmpty()) return@runCatching null

            val blockResponse =
                httpClient.get("block/$blockHash") {
                    contentType(ContentType.Application.Json)
                }
            if (!blockResponse.status.isSuccess()) return@runCatching null

            val block = blockResponse.body<BlockResponse>()
            block.medianTime
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            null
        }

    override suspend fun getUtxos(address: String): List<BoardingUtxo> =
        runCatching {
            val response = httpClient.get("address/$address/utxo")

            if (!response.status.isSuccess()) {
                throw Exception("Failed to fetch utxos for address $address")
            }

            val utxos = response.body<List<Utxo>>()
            utxos.map { utxo ->
                BoardingUtxo(
                    txId = TxId(utxo.txId),
                    vOut = utxo.vOut,
                    amount = utxo.value,
                    confirmed = utxo.status.confirmed,
                    blockHeight = utxo.status.blockHeight,
                    blockTime = utxo.status.blockTime,
                )
            }
        }.getOrElse { throwable -> throw throwable }

    override suspend fun broadcast(tx: Transaction): Boolean =
        runCatching {
            val response =
                httpClient.post("tx") {
                    contentType(ContentType.Text.Plain)
                    setBody(tx.toString())
                }
            if (!response.status.isSuccess()) {
                val error = response.status.description
                Log.warning(LOG_TAG, "Esplora broadcast failed for tx ${tx.txid}: $error")
                return@runCatching false
            }
            true
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            Log.warning(LOG_TAG, "Failed to broadcast tx ${tx.txid} via Esplora")
            false
        }

    override suspend fun broadcastPackage(
        parent: Transaction,
        child: Transaction,
    ): Boolean =
        runCatching {
            val response =
                httpClient.post("txs/package") {
                    contentType(ContentType.Application.Json)
                    val txs =
                        JsonArray(
                            listOf(
                                JsonPrimitive(parent.toString()),
                                JsonPrimitive(child.toString()),
                            ),
                        )
                    setBody(txs)
                }

            if (
                response.status == HttpStatusCode.NotImplemented ||
                response.status == HttpStatusCode.BadRequest ||
                response.status == HttpStatusCode.MethodNotAllowed ||
                response.status == HttpStatusCode.NotFound
            ) {
                return@runCatching broadcastPackageFallback(parent, child)
            }

            if (!response.status.isSuccess()) {
                val error = response.status.description
                Log.warning(LOG_TAG, "Esplora package broadcast failed: $error")
                return@runCatching false
            }

            true
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            false
        }

    override suspend fun getTxStatus(txId: TxId): Blockchain.TxStatus =
        runCatching {
            val response =
                httpClient.get("tx/$txId/status") {
                    contentType(ContentType.Application.Json)
                }
            if (!response.status.isSuccess()) {
                throw Exception("Failed to fetch status for tx $txId")
            }
            val status = response.body<TxStatus>()
            Blockchain.TxStatus(
                status.confirmed,
                status.blockHeight?.takeIf { status.confirmed }?.toInt(),
                !status.confirmed,
            )
        }.getOrElse { throwable -> throw throwable }

    override suspend fun estimateFeeRate(confirmTarget: Int): Float =
        runCatching {
            val response =
                httpClient.get("fee-estimates") {
                    contentType(ContentType.Application.Json)
                }
            if (!response.status.isSuccess()) {
                throw Exception("Failed to fetch fee estimates")
            }
            val estimates = response.body<Map<String, Float>>()
            val feeRate = estimates[confirmTarget.toString()]
            if (feeRate != null) return@runCatching feeRate

            val closest =
                estimates
                    .map {
                        it.key.toInt() to it.value
                    }.filter {
                        it.second < Int.MAX_VALUE
                    }.minBy {
                        abs(it.first - confirmTarget)
                    }
            if (closest.second > 0) ceil(closest.second) else 2f
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            Log.warning(LOG_TAG, "Failed to estimate fee rate via Esplora, using fallback")
            2f
        }

    override suspend fun getRawTx(txId: TxId): Transaction? =
        runCatching {
            val response = httpClient.get("tx/$txId/hex")
            if (!response.status.isSuccess()) return null

            val txHex = response.bodyAsText().trim()
            val tx = Transaction.read(txHex)
            if (tx.txid != txId) return null
            tx
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            Log.debug(LOG_TAG, "Failed to fetch raw transaction $txId from Esplora")
            null
        }

    private suspend fun broadcastPackageFallback(
        parent: Transaction,
        child: Transaction,
    ): Boolean {
        Log.debug(LOG_TAG, "Esplora: broadcasting package as sequential txs (parent then child)")
        val isParentSuccessful = broadcast(parent)
        if (!isParentSuccessful) {
            Log.warning(LOG_TAG, "Esplora package fallback: parent broadcast failed for tx ${parent.txid}")
            return false
        }
        val isChildSuccessful = broadcast(child)
        if (!isChildSuccessful) {
            // Child can be rejected when parent fee was enough on its own, this is still a valid outcome
            // We still return true
            Log.warning(
                LOG_TAG,
                "Esplora package fallback: child broadcast failed for tx ${child.txid} (parent ${parent.txid} was accepted)",
            )
        }
        return true
    }

    companion object {
        private const val LOG_TAG = "Esplora"
    }

    @Serializable
    private data class BlockResponse(
        val height: Long,
        @SerialName("mediantime")
        val medianTime: Long,
    )

    @Serializable
    private data class Utxo(
        @SerialName("txid")
        val txId: String,
        @SerialName("vout")
        val vOut: Int,
        val value: Long,
        val status: UtxoStatus,
    )

    @Serializable
    private data class UtxoStatus(
        val confirmed: Boolean,
        @SerialName("block_height")
        val blockHeight: Long?,
        @SerialName("block_time")
        val blockTime: Long?,
    )

    @Serializable
    private data class TxStatus(
        val confirmed: Boolean,
        @SerialName("block_height")
        val blockHeight: Long? = null,
    )
}
