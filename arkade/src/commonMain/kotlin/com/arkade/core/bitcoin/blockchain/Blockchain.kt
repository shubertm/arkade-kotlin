package com.arkade.core.bitcoin.blockchain

import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import org.koin.core.component.getScopeName

/** Provides Bitcoin chain data, transaction lookup, fee estimates, and broadcasting. */
interface Blockchain {
    /** Returns the provider's current chain time in Unix seconds and block height. */
    suspend fun getChainTime(): ChainTime

    /**
     * Returns median time past at [blockHeight], in Unix seconds, or `null` when unavailable.
     */
    suspend fun getMedianTimePast(blockHeight: Long): Long?

    /** Returns unspent outputs for [address], including any available confirmation metadata. */
    suspend fun getUtxos(address: String): List<BoardingUtxo>

    /** Broadcasts [tx] and returns the provider's result. */
    suspend fun broadcast(tx: Transaction): Boolean

    /** Broadcasts a dependent [parent] and [child] package and returns the provider's result. */
    suspend fun broadcastPackage(
        parent: Transaction,
        child: Transaction,
    ): Boolean

    /** Returns the provider's confirmation and mempool status for [txId]. */
    suspend fun getTxStatus(txId: TxId): TxStatus

    /**
     * Estimates the fee rate for confirmation within [confirmTarget] blocks.
     *
     * @param confirmTarget The desired confirmation target in blocks.
     * @return The estimated fee rate in satoshis per virtual byte.
     */
    suspend fun estimateFeeRate(confirmTarget: Int = 6): Float

    /**
     * Fetches and decodes the transaction identified by [txId], or returns `null` when unavailable.
     *
     * @throws UnsupportedOperationException If the provider does not implement transaction lookup.
     */
    suspend fun getRawTx(txId: TxId): Transaction? =
        throw UnsupportedOperationException("${this.getScopeName().type.simpleName} cannot fetch raw transactions by txid.")

    /**
     * Transaction confirmation and mempool status reported by a provider.
     *
     * @property confirmed Whether the transaction has been included in a block.
     * @property blockHeight Confirmation block height, or `null` when unavailable.
     * @property isInMempool Whether the provider considers the transaction to be in its mempool.
     */
    data class TxStatus(
        val confirmed: Boolean,
        val blockHeight: Int?,
        val isInMempool: Boolean,
    )
}

/**
 * A provider's view of chain time and height.
 *
 * @property time Chain timestamp in Unix seconds; [EsploraBlockchain] uses the tip's median time past.
 * @property height Chain tip height.
 */
data class ChainTime(
    val time: Long,
    val height: Long,
)

/**
 * An unspent Bitcoin output with optional confirmation metadata.
 *
 * Block height and timestamp default to `null` when omitted. [NBXplorerBlockchain] infers the
 * height from positive confirmation counts and fetches the block header's timestamp when available;
 * a failed timestamp lookup can leave the height populated while the timestamp remains `null`.
 *
 * @property txId Transaction containing the output.
 * @property vOut Zero-based output index within the transaction.
 * @property amount Output value in satoshis.
 * @property confirmed Whether the output's transaction has been included in a block.
 * @property blockHeight Confirmation block height, or `null` when unavailable.
 * @property blockTime Confirmation block timestamp in Unix seconds, or `null` when unavailable.
 */
data class BoardingUtxo(
    val txId: TxId,
    val vOut: Int,
    val amount: Long,
    val confirmed: Boolean,
    val blockHeight: Long? = null,
    val blockTime: Long? = null,
)
