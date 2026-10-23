package com.arkade.core.bitcoin.blockchain

import com.arkade.core.tryPut
import com.arkade.network.http.getHttpClient
import com.arkade.network.jsonrpc.RpcException
import com.arkade.network.jsonrpc.sendRpc
import com.arkade.utils.Log
import com.arkade.utils.debug
import com.arkade.utils.warning
import com.ionspin.kotlin.bignum.decimal.toBigDecimal
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.ceil

class RPCBlockchain private constructor() : Blockchain {
    /**
     * Creates a provider using [httpClient], configured with the RPC endpoint and JSON content
     * negotiation. The provider does not close the client.
     */
    constructor(httpClient: HttpClient) : this() {
        this.httpClient = httpClient
    }

    /**
     * Creates a provider using [getHttpClient] for the RPC endpoint at [baseUrl].
     * A trailing slash is added when absent.
     *
     * @throws NotImplementedError On native platforms, where HTTP client creation is not implemented.
     */
    constructor(baseUrl: String) : this() {
        httpClient = getHttpClient(baseUrl)
    }

    private lateinit var httpClient: HttpClient

    private val blockTimeByBlockHash: MutableMap<ByteVector32, BlockTime> = mutableMapOf()
    private val mutex = Mutex()

    /**
     * Returns the chain tip's block timestamp in Unix seconds and height from Bitcoin Core.
     * RPC, HTTP, transport, and decoding failures propagate, including coroutine cancellation.
     *
     * @throws Exception If the response omits the timestamp or height.
     */
    override suspend fun getChainTime(): ChainTime {
        val response = httpClient.sendRpc<JsonElement>(method = "getblockchaininfo")

        val time =
            response
                ?.jsonObject
                ?.get("time")
                ?.jsonPrimitive
                ?.long
        val height =
            response
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
     * Returns median time past at [blockHeight] in Unix seconds, or `null` if the block header
     * lacks its timestamp or median time, or an RPC error occurs. Other HTTP, transport, and
     * decoding failures propagate, including coroutine cancellation.
     *
     * @throws Exception If the block hash or header result is missing.
     */
    override suspend fun getMedianTimePast(blockHeight: Long): Long? =
        try {
            blockTime(blockHeight).medianTime
        } catch (_: RpcException) {
            null
        }

    /**
     * @throws UnsupportedOperationException Always, because Bitcoin Core RPC does not support
     * address-indexed UTXO discovery. Use [NBXplorerBlockchain] or [EsploraBlockchain] instead.
     */
    override suspend fun getUtxos(address: String): List<BoardingUtxo> =
        throw UnsupportedOperationException(
            "Bitcoin Core RPC has no native address-indexed UTXO API. Use NBXplorerBlockchain or EsploraBlockchain when boarding-UTXO discovery is required.",
        )

    /**
     * Submits [tx] and returns `true` if the RPC call completes without error, even with a null result.
     * RPC, HTTP, transport, and decoding failures return `false`; coroutine cancellation propagates.
     */
    override suspend fun broadcast(tx: Transaction): Boolean =
        try {
            httpClient.sendRpc<String>(
                "sendrawtransaction",
                JsonArray(listOf(JsonPrimitive(tx.toString()))),
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.warning(LOG_TAG, "RPC broadcast failed for tx: ${tx.txid}")
            false
        }

    /**
     * Submits a dependent [parent] and [child] package. Returns `true` when `package_msg` is
     * `success` or absent, including a null result; any other message returns `false`.
     *
     * RPC, HTTP, transport, or decoding failures trigger sequential broadcasting, parent first.
     * The fallback returns `true` if the parent broadcast succeeds, even if the child fails.
     * Coroutine cancellation propagates.
     */
    override suspend fun broadcastPackage(
        parent: Transaction,
        child: Transaction,
    ): Boolean =
        try {
            val txs =
                JsonArray(
                    listOf(
                        JsonPrimitive(parent.toString()),
                        JsonPrimitive(child.toString()),
                    ),
                )
            val response =
                httpClient.sendRpc<JsonElement>(
                    "submitpackage",
                    JsonArray(listOf(txs)),
                )
            val packageMsg = response?.jsonObject["package_msg"]?.jsonPrimitive?.content
            if (packageMsg != null && packageMsg != "success") {
                Log.warning(LOG_TAG, "Transaction package was rejected")
                return false
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            Log.warning(LOG_TAG, "submitpackage failed: ${e.message}")
            broadcastPackageFallback(parent, child)
        } catch (_: Exception) {
            // submitpackage requires Bitcoin Core 28+; fall back to sequential.
            Log.warning(LOG_TAG, "submitpackage failed for parent ${parent.txid}, falling back to sequential broadcast")
            broadcastPackageFallback(parent, child)
        }

    /**
     * Returns confirmation status for [txId], looking up its block height when confirmed.
     * Missing or nonpositive confirmations are treated as unconfirmed and in the mempool;
     * a confirmed transaction's height may be `null` when unavailable.
     *
     * An RPC error from either lookup returns unconfirmed, no height, and not in the mempool.
     * Other HTTP, transport, and decoding failures propagate, including coroutine cancellation.
     */
    override suspend fun getTxStatus(txId: TxId): Blockchain.TxStatus {
        return try {
            val response =
                httpClient.sendRpc<JsonElement>(
                    "getrawtransaction",
                    JsonArray(
                        listOf(
                            JsonPrimitive(txId.toString()),
                            JsonPrimitive(true),
                        ),
                    ),
                )
            val confirmations = response?.jsonObject["confirmations"]?.jsonPrimitive?.long ?: 0
            if (confirmations <= 0) return Blockchain.TxStatus(false, null, true)

            val blockHash = response?.jsonObject["blockhash"]?.jsonPrimitive?.content
            var blockHeight: Int? = null
            if (blockHash != null) {
                val blockHeader =
                    httpClient.sendRpc<JsonElement>(
                        "getblockheader",
                        JsonArray(
                            listOf(
                                JsonPrimitive(blockHash),
                            ),
                        ),
                    )
                blockHeight = blockHeader?.jsonObject["height"]?.jsonPrimitive?.intOrNull
            }
            Blockchain.TxStatus(true, blockHeight, false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RpcException) {
            Blockchain.TxStatus(false, null, false)
        }
    }

    /**
     * Estimates the fee rate in satoshis per virtual byte for confirmation within [confirmTarget]
     * blocks, rounding up to a whole satoshi per virtual byte. Returns `2f` if the estimate is
     * absent or RPC, HTTP, transport, or decoding fails. Coroutine cancellation propagates.
     */
    override suspend fun estimateFeeRate(confirmTarget: Int): Float =
        try {
            val response =
                httpClient.sendRpc<JsonElement>(
                    "estimatesmartfee",
                    JsonArray(
                        listOf(
                            JsonPrimitive(confirmTarget),
                        ),
                    ),
                )
            val feeRate =
                response
                    ?.jsonObject["feerate"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toBigDecimal()
            feeRate?.multiply(100_000.toBigDecimal())?.ceil()?.floatValue() ?: 2f
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.warning(LOG_TAG, "estimatesmartfee failed, using fallback: ${e.message}")
            2f
        }

    /**
     * Fetches and decodes the transaction identified by [txId]. Returns `null` for an absent or
     * blank result, a mismatched transaction ID, or RPC, HTTP, transport, or decoding failures.
     * Coroutine cancellation propagates.
     */
    override suspend fun getRawTx(txId: TxId): Transaction? {
        return try {
            val txHex =
                httpClient.sendRpc<String>(
                    "getrawtransaction",
                    JsonArray(
                        listOf(
                            JsonPrimitive(txId.toString()),
                        ),
                    ),
                )

            if (txHex.isNullOrBlank()) return null

            val tx = Transaction.read(txHex)
            if (tx.txid != txId) return null
            tx
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.debug(LOG_TAG, "Failed to fetch raw transaction $txId over RPC")
            null
        }
    }

    /**
     * Returns block times in Unix seconds at [height], reusing complete results by block hash.
     * Both times are `null` if the timestamp is missing; only median time is `null` if it is missing.
     * RPC, HTTP, transport, and decoding failures propagate, including coroutine cancellation.
     *
     * @throws Exception If the block hash or header result is missing.
     */
    private suspend fun blockTime(height: Long): BlockTime {
        val blockHashHex =
            httpClient.sendRpc<String>("getblockhash", JsonArray(listOf(JsonPrimitive(height))))
                ?: throw Exception("Failed to get block hash at height: $height")

        val blockHash = ByteVector32.fromValidHex(blockHashHex)
        val cachedBlockTime = mutex.withLock { blockTimeByBlockHash[blockHash] }
        if (cachedBlockTime != null) {
            return cachedBlockTime
        }

        val blockHeader =
            httpClient.sendRpc<JsonElement>("getblockheader", JsonArray(listOf(JsonPrimitive(blockHashHex))))
                ?: throw Exception("Failed to get block header: $blockHash")
        val timestamp =
            blockHeader.jsonObject["time"]?.jsonPrimitive?.long ?: return BlockTime(null, null)
        val medianTime =
            blockHeader.jsonObject["mediantime"]?.jsonPrimitive?.long ?: return BlockTime(
                timestamp,
                null,
            )
        val blockTime = BlockTime(timestamp, medianTime)

        mutex.withLock { blockTimeByBlockHash.tryPut(blockHash, blockTime) }

        return blockTime
    }

    /**
     * Broadcasts [parent] first, then [child] only if the parent succeeds. Returns whether the
     * parent broadcast succeeded, regardless of the child's result. Coroutine cancellation propagates.
     */
    private suspend fun broadcastPackageFallback(
        parent: Transaction,
        child: Transaction,
    ): Boolean {
        if (!broadcast(parent)) return false
        if (!broadcast(child)) {
            Log.debug(LOG_TAG, "Sequential fallback: child CPFP broadcast failed, but parent was accepted")
        }
        return true
    }

    companion object {
        private const val LOG_TAG = "RPCBlockchain"
    }
}
