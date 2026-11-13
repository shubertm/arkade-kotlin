package com.arkade.core.batches

import com.arkade.core.getArkFieldsCosigners
import com.arkade.core.wallet.signer.Signer
import com.arkade.core.wallet.signer.SignerSession
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.crypto.musig2.AggregatedNonce
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.bitcoin.crypto.musig2.SecretNonce
import fr.acinq.bitcoin.sat

/**
 * Manages this wallet's MuSig2 signing lifecycle for a single VTXO [graph] being cooperatively
 * signed with the Ark server and the other cosigners.
 *
 * The session's methods must be driven through a fixed lifecycle:
 * 1. [getNonces] must be called first. It generates (once) and returns this wallet's public
 *    nonces for every transaction it cosigns, to be submitted to the server.
 * 2. [aggregateNonces] is called as the other cosigners' nonces for a given transaction become
 *    available (e.g. from [BatchEvent.TreeNoncesEvent]); it aggregates them with this wallet's
 *    own nonce for that transaction.
 * 3. [verifyAggregatedNonces] is called once the server reports the final aggregated nonces
 *    (e.g. from [BatchEvent.TreeNoncesAggregatedEvent]), confirming they match what was
 *    aggregated locally in step 2.
 * 4. [sign] produces this wallet's partial signature for every transaction it has a nonce for.
 *
 * Calling [aggregateNonces], [verifyAggregatedNonces], or [sign] before [getNonces], calling
 * [getNonces] more than once, or calling [sign]/[verifyAggregatedNonces] before the relevant
 * nonces have been aggregated, throws [UnsupportedOperationException].
 *
 * @property wallet Used to derive this session's key material and produce partial signatures.
 * @property graph The VTXO tree being signed.
 * @property descriptor The output descriptor identifying the key this session signs with.
 * @property tapScriptTree The sweep tap script tree shared by every node's Taproot output.
 * @property rootSharedOutputAmount The amount of the commitment transaction's shared output
 * funding the root of [graph].
 */
class TreeSignerSession(
    private val signer: Signer,
) : SignerSession {
    private lateinit var graph: TxTree
    private lateinit var descriptor: String
    private lateinit var tapScriptTree: ScriptTree
    private lateinit var tapScriptTreeRoot: ByteVector32

    private var rootSharedOutputAmount: Long = 0
    private var myNonces: Map<ByteVector32, Pair<SecretNonce, IndividualNonce>>? = null
    private val treeNonces: MutableMap<ByteVector32, List<IndividualNonce>> = mutableMapOf()
    private val aggregatedNonces: MutableMap<ByteVector32, AggregatedNonce> = mutableMapOf()

    override fun init(
        graph: TxTree,
        descriptor: String,
        tapScriptTree: ScriptTree,
        rootSharedOutputAmount: Long,
    ) {
        this.graph = graph
        this.descriptor = descriptor
        this.tapScriptTree = tapScriptTree
        this.tapScriptTreeRoot = tapScriptTree.hash()
        this.rootSharedOutputAmount = rootSharedOutputAmount
    }

    /**
     * Generates (on first call) and returns this wallet's public nonces for every transaction in
     * [graph] whose cosigners include this wallet's key.
     *
     * Must be called, and its result submitted to the server, before [aggregateNonces],
     * [verifyAggregatedNonces], or [sign].
     *
     * @return This wallet's public nonces, keyed by transaction id.
     */
    override suspend fun getNonces(): Map<ByteVector32, IndividualNonce> {
        if (myNonces == null) {
            myNonces = generateNonces()
        }
        return myNonces?.mapValues { it.value.second }!!
    }

    /**
     * Aggregates the cosigners' [treeNonces] for [txId] with this wallet's own nonce for that
     * transaction, storing the result for later use by [verifyAggregatedNonces] and [sign].
     *
     * @param treeNonces All cosigners' public nonces for [txId], which must include this
     * wallet's own nonce as returned by [getNonces].
     * @param txId The id of the transaction the nonces are for.
     * @throws UnsupportedOperationException if [getNonces] has not been called yet, if this
     * wallet has no nonce for [txId], if [treeNonces] does not include this wallet's own nonce,
     * or if aggregation fails.
     */
    override fun aggregateNonces(
        treeNonces: List<IndividualNonce>,
        txId: TxId,
    ) {
        val txId = txId.value
        val myNonce = myNonces?.get(txId)
        if (myNonces == null || myNonce == null) {
            throw UnsupportedOperationException("Missing private nonce")
        }
        if (!treeNonces.any { nonce -> nonce.data == myNonce.second.data }) {
            throw UnsupportedOperationException("Missing my nonce")
        }

        val aggregatedNonce =
            IndividualNonce.aggregate(treeNonces).right
                ?: throw UnsupportedOperationException("Failed to aggregate nonces")
        this.treeNonces[txId] = treeNonces
        aggregatedNonces[txId] = aggregatedNonce
    }

    /**
     * Verifies that every aggregated nonce in [expected] (as reported by the server) matches
     * the nonce this session locally aggregated via [aggregateNonces].
     *
     * @param expected The server-reported aggregated nonces, keyed by transaction id.
     * @throws UnsupportedOperationException if [getNonces] has not been called yet, if a
     * transaction in [expected] has not been locally aggregated via [aggregateNonces], or if
     * any aggregated nonce does not match.
     */
    override fun verifyAggregatedNonces(expected: Map<ByteVector32, IndividualNonce>) {
        if (myNonces == null) {
            throw UnsupportedOperationException("Nonces not generated")
        }
        val isMatching =
            expected.all { entry ->
                val nonce =
                    aggregatedNonces[entry.key]
                        ?: throw UnsupportedOperationException("Aggregated nonce missing")
                nonce.data == entry.value.data
            }
        if (!isMatching) {
            throw UnsupportedOperationException("Aggregated myNonces do not match")
        }
    }

    /**
     * Produces this wallet's partial MuSig2 signature for every transaction in [graph] it has a
     * nonce for.
     *
     * @return The partial signatures, keyed by transaction id.
     * @throws UnsupportedOperationException if [getNonces] has not been called yet, or if
     * signing a transaction is attempted before its nonces were aggregated via
     * [aggregateNonces].
     */
    override suspend fun sign(): Map<ByteVector32, ByteVector32> {
        if (myNonces == null) {
            throw UnsupportedOperationException("Nonces not generated")
        }

        val signatures: MutableMap<ByteVector32, ByteVector32> = mutableMapOf()
        graph.forEach { nodeTxTree ->
            val txId = nodeTxTree.root.global.tx.txid.value
            if (myNonces?.containsKey(txId) == false) {
                return@forEach
            }
            val signature = signPartial(nodeTxTree)
            signatures[txId] = signature
        }
        return signatures
    }

    /**
     * Generates a fresh public/private nonce pair for every transaction in [graph] whose
     * cosigners (see [getCosignerPubKeys]) include this session's own key.
     *
     * @return The generated nonce pairs, keyed by transaction id.
     * @throws UnsupportedOperationException if nonces have already been generated for this
     * session.
     */
    private suspend fun generateNonces(): Map<ByteVector32, Pair<SecretNonce, IndividualNonce>> {
        if (myNonces != null) {
            throw UnsupportedOperationException("Nonces already generated")
        }
        val myPubKey = signer.xOnlyPublicKey(descriptor)

        val newNonces: MutableMap<ByteVector32, Pair<SecretNonce, IndividualNonce>> = mutableMapOf()

        graph.forEach { node ->
            val tx = node.root.global.tx
            val txId = tx.txid

            val cosignersKeys = node.getCosignerPubKeys()

            if (cosignersKeys.all { it.xOnly() != myPubKey }) {
                return@forEach
            }

            val prevOut = getPrevOutput(node, graph)

            val sigHash =
                tx.hashForSigningTaprootKeyPath(
                    0,
                    listOf(prevOut),
                    SigHash.SIGHASH_DEFAULT,
                )

            val nonce =
                signer.generateNonce(
                    txId.value,
                    descriptor,
                    cosignersKeys,
                    sigHash,
                )
            newNonces[txId.value] = nonce
        }
        return newNonces
    }

    /**
     * Derives the previous output spent by [graph]'s root transaction.
     *
     * For the root of [rootGraph], this is the commitment transaction's shared output
     * ([rootSharedOutputAmount] at the aggregated-key Taproot script). For any other node, it is
     * looked up as the corresponding output of its parent transaction within [rootGraph], with
     * the same aggregated-key Taproot script.
     *
     * @param graph The (sub)tree whose root's previous output is being derived.
     * @param rootGraph The full tree, used to resolve non-root parents.
     * @return The previous [TxOut] spent by [graph]'s root transaction.
     * @throws UnsupportedOperationException if [graph]'s parent transaction cannot be found in
     * [rootGraph].
     */
    private fun getPrevOutput(
        graph: TxTree,
        rootGraph: TxTree,
    ): TxOut {
        val cosignerKeys = graph.getCosignerPubKeys()

        val aggregatedKey = Musig2.aggregateKeys(cosignerKeys)

        val txId = graph.root.global.tx.txid

        val scriptPubKey = Script.pay2tr(aggregatedKey, tapScriptTreeRoot)

        if (txId == rootGraph.root.global.tx.txid) {
            return TxOut(rootSharedOutputAmount.sat(), scriptPubKey)
        }

        val tx = graph.root.global.tx
        val parentInput = tx.txIn[0]
        val parentTxId = parentInput.outPoint.txid
        val parent =
            rootGraph.find(parentTxId)
                ?: throw UnsupportedOperationException("Parent tx not found: $parentTxId")

        val parentOutput = parent.root.global.tx.txOut[parentInput.outPoint.index.toInt()]

        return TxOut(parentOutput.amount, scriptPubKey)
    }

    /**
     * Produces this wallet's partial MuSig2 signature for [nodeTxTree]'s root transaction, using
     * the private nonce generated for it and the tree nonces aggregated for it.
     *
     * @param nodeTxTree The tree node whose root transaction is being signed.
     * @return The partial signature.
     * @throws UnsupportedOperationException if nonces have not been generated, if no private
     * nonce was generated for this transaction, or if [aggregateNonces] has not been called for
     * this transaction yet.
     */
    private suspend fun signPartial(nodeTxTree: TxTree): ByteVector32 {
        if (myNonces == null) {
            throw UnsupportedOperationException("Session not properly initialized")
        }

        val tx = nodeTxTree.root.global.tx
        val txId = tx.txid.value

        val privNonce = myNonces!![txId]?.first ?: throw UnsupportedOperationException("Missing private nonce")

        val prevOut = getPrevOutput(nodeTxTree, graph)

        val cosignerPubKeys = nodeTxTree.getCosignerPubKeys()

        val pubNonces = treeNonces[txId] ?: throw UnsupportedOperationException("Missing tree nonces")

        val partialSig =
            signer.signMusig(
                descriptor,
                tx,
                listOf(prevOut),
                0,
                privNonce,
                cosignerPubKeys,
                pubNonces,
                tapScriptTree,
            )

        return partialSig
    }

    /**
     * Extracts this node's cosigner public keys from its root transaction's first input,
     * ordered by their declared cosigner index.
     *
     * @see com.arkade.core.getArkFieldsCosigners
     */
    private fun TxTree.getCosignerPubKeys(): List<PublicKey> =
        root.inputs[0]
            .getArkFieldsCosigners()
            .sortedBy { it.index }
            .map { it.pubKey }

    companion object {
        private const val LOG_TAG = "TreeSignerSession"
    }
}
