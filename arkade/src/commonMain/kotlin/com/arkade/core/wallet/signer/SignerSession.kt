package com.arkade.core.wallet.signer

import com.arkade.core.batches.TxTree
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce

/**
 * Marker interface for an interactive signing session obtained from [Signer.signerSession].
 */
interface SignerSession {
    fun init(
        graph: TxTree,
        descriptor: String,
        tapScriptTree: ScriptTree,
        rootSharedOutputAmount: Long,
    )

    suspend fun getNonces(): Map<ByteVector32, IndividualNonce>

    fun aggregateNonces(
        treeNonces: List<IndividualNonce>,
        txId: TxId,
    )

    fun verifyAggregatedNonces(expected: Map<ByteVector32, IndividualNonce>)

    suspend fun sign(): Map<ByteVector32, ByteVector32>
}
