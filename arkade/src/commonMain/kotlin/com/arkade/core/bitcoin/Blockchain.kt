package com.arkade.core.bitcoin

import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId

interface Blockchain {
    /** Returns the provider's current chain timestamp and block height. */
    fun getChainTime(): ChainTime

    /** Returns the current median time past reported by the provider. */
    fun getMedianTimePast(): Long

    /** Returns the boarding outputs currently known to the provider. */
    fun getUtxos(): List<BoardingUtxo>

    /** Broadcasts [tx] and returns the provider's result. */
    fun broadcast(tx: Transaction): Boolean

    /** Broadcasts a dependent [parent] and [child] package and returns the provider's result. */
    fun broadcastPackage(
        parent: Transaction,
        child: Transaction,
    ): Boolean

    /** Returns the provider's confirmation and mempool status for [txId]. */
    fun getTxStatus(txId: TxId): TxStatus

    /**
     * Estimates the fee rate for confirmation within [confirmTarget] blocks.
     *
     * @param confirmTarget The desired confirmation target in blocks.
     * @return The estimated fee rate reported by the provider.
     */
    fun estimateFeeRate(confirmTarget: Int = 6): Float
}

data class ChainTime(
    val time: Long,
    val height: Long,
)

data class TxStatus(
    val confirmed: Boolean,
    val blockHeight: Int?,
    val isInMempool: Boolean,
)

data class BoardingUtxo(
    val txId: TxId,
    val vOut: Int,
    val amount: Long,
    val confirmed: Boolean,
    val blockHeight: Long,
    val blockTime: Long,
)
