package com.arkade.core.wallet.signer

import com.arkade.core.batches.TxTree
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce

/**
 * Interface for an interactive multi-party signing session (such as a MuSig2 VTXO tree signing session)
 * obtained from [Signer.signerSession].
 *
 * Provides lifecycle methods to initialize session parameters, generate local public nonces,
 * aggregate nonces from cosigners, verify server-aggregated nonces, and generate partial signatures.
 */
interface SignerSession {
    /**
     * Initializes this signing session with the target transaction tree, descriptor, script tree,
     * and root shared output amount.
     *
     * Must be called before calling [getNonces] or subsequent session methods.
     *
     * @param graph The VTXO transaction tree being signed.
     * @param descriptor The output descriptor identifying the key material used to sign.
     * @param tapScriptTree The sweep tap script tree shared across nodes.
     * @param rootSharedOutputAmount The amount of the commitment transaction's shared output funding the root of [graph].
     */
    fun init(
        graph: TxTree,
        descriptor: String,
        tapScriptTree: ScriptTree,
        rootSharedOutputAmount: Long,
    )

    /**
     * Generates (on first call) and returns public nonces for all transactions in the session graph
     * that require this participant's signature.
     *
     * @return Map of transaction IDs to individual public nonces.
     */
    suspend fun getNonces(): Map<ByteVector32, IndividualNonce>

    /**
     * Aggregates public nonces received from all cosigners for a specific transaction.
     *
     * @param treeNonces The list of individual public nonces from all cosigners for [txId].
     * @param txId The transaction ID for which nonces are being aggregated.
     */
    fun aggregateNonces(
        treeNonces: List<IndividualNonce>,
        txId: TxId,
    )

    /**
     * Verifies locally aggregated nonces against the expected aggregated nonces provided by the coordinator/server.
     *
     * @param expected Map of transaction IDs to expected aggregated public nonces.
     */
    fun verifyAggregatedNonces(expected: Map<ByteVector32, IndividualNonce>)

    /**
     * Generates partial signatures for all transactions in the session graph that this participant cosigns.
     *
     * @return Map of transaction IDs to partial signature byte vectors.
     */
    suspend fun sign(): Map<ByteVector32, ByteVector32>
}
