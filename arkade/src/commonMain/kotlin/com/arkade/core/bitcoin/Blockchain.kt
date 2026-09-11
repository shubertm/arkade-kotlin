package com.arkade.core.bitcoin

import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId

interface Blockchain {
    fun getChainTime(): ChainTime

    fun getMedianTimePast(): Long

    fun getUtxos(): List<BoardingUtxo>

    fun broadcast(tx: Transaction): Boolean

    fun broadcastPackage(
        parent: Transaction,
        child: Transaction,
    ): Boolean

    fun getTxStatus(txId: TxId): TxStatus

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
