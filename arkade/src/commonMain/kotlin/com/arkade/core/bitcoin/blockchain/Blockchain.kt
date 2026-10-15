package com.arkade.core.bitcoin.blockchain

import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import org.koin.core.component.getScopeName

interface Blockchain {
    /** Returns the provider's current chain timestamp and block height. */
    suspend fun getChainTime(): ChainTime

    /** Returns the current median time past reported by the provider. */
    suspend fun getMedianTimePast(blockHeight: Long): Long?

    /** Returns the boarding outputs currently known to the provider. */
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
     * @return The estimated fee rate reported by the provider.
     */
    suspend fun estimateFeeRate(confirmTarget: Int = 6): Float

    suspend fun getRawTx(txId: TxId): Transaction? =
        throw UnsupportedOperationException("${this.getScopeName().type.simpleName} cannot fetch raw transactions by txid.")

    data class TxStatus(
        val confirmed: Boolean,
        val blockHeight: Int?,
        val isInMempool: Boolean,
    )
}

data class ChainTime(
    val time: Long,
    val height: Long,
)

data class BoardingUtxo(
    val txId: TxId,
    val vOut: Int,
    val amount: Long,
    val confirmed: Boolean,
    val blockHeight: Long?,
    val blockTime: Long?,
)
