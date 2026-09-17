package com.arkade.core.batches

import com.arkade.core.ArkServerInfo
import com.arkade.core.ArkTransactionBuilder
import com.arkade.core.assets.Extension
import com.arkade.core.assets.Extension.Companion.isExtension
import com.arkade.core.assets.Packet
import com.arkade.core.buildScriptTree
import com.arkade.core.coins.ArkCoin
import com.arkade.core.csvSigScript
import com.arkade.core.intents.ArkIntent
import com.arkade.core.intents.RegisterIntentMessage
import com.arkade.core.isUnSpendable
import com.arkade.core.toXOnlyPubKey
import com.arkade.core.wallet.Wallet
import com.arkade.network.ArkadeClient
import com.arkade.utils.Log
import com.arkade.utils.info
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.utils.getOrElse
import kotlin.io.encoding.Base64

/**
 * Drives a single batch through its lifecycle by reacting to [BatchEvent]s and implementing
 * [BatchEventHandler].
 *
 * A session is created per batch that [inputs] have been registered for via [intent], and is
 * responsible for finalizing the batch (building/signing forfeit transactions and signing the
 * commitment transaction for boarding inputs) and, eventually, cooperatively signing the
 * resulting VTXO tree.
 *
 * @property client Used to fetch server info and submit signed forfeit/commitment transactions.
 * @property wallet Used to sign forfeit transactions and the commitment transaction.
 * @property intent The intent that registered [inputs] for this batch.
 * @property inputs The coins being spent/registered in this batch.
 * @property batchStartedEvent The event that started this batch, providing [batchId] and expiry.
 */
class BatchSession(
    private val client: ArkadeClient,
    private val wallet: Wallet,
    private val intent: ArkIntent,
    private val inputs: List<ArkCoin>,
    private val batchStartedEvent: BatchEvent.BatchStartedEvent,
) : BatchEventHandler {
    private val batchId = batchStartedEvent.id
    private val intentParameters = RegisterIntentMessage.fromString(intent.registerProofMessage)

    private val signerDescriptor = intent.signerDescriptor
    private lateinit var sweepTapTree: ScriptTree
    private val vtxos: MutableList<TxTreeNode> = mutableListOf()
    private val connectors: MutableList<TxTreeNode> = mutableListOf()

    private lateinit var serverInfo: ArkServerInfo

    private var signerSession: TreeSignerSession? = null

    var isComplete = false
        private set

    /**
     * Fetches [serverInfo] from [client] and derives [sweepTapTree] from the batch's expiry
     * and the server's forfeit public key.
     *
     * Must be called before [processEvent] handles a [BatchEvent.BatchFinalizationEvent].
     */
    suspend fun init() {
        serverInfo = client.getInfo()
        val unilateralExitScript = csvSigScript(batchStartedEvent.batchExpiry.inWholeSeconds, serverInfo.forfeitPubKey)
        sweepTapTree = buildScriptTree(listOf(unilateralExitScript))
    }

    /**
     * Processes a batch event and tracks whether this session has completed.
     *
     * @param event The batch event to process.
     * @return `true` if the session is already complete or the event finalizes this batch, `false` otherwise.
     * @throws UnsupportedOperationException if the batch fails.
     */
    suspend fun processEvent(event: BatchEvent): Boolean {
        if (isComplete) return true
        try {
            when (event) {
                is BatchEvent.StreamStartedEvent -> {}

                is BatchEvent.BatchStartedEvent -> {}

                is BatchEvent.BatchFinalizedEvent -> {
                    if (event.id == batchId) {
                        isComplete = true
                        return true
                    }
                }

                is BatchEvent.BatchFinalizationEvent -> {
                    onBatchFinalization(event, connectors)
                }

                is BatchEvent.BatchFailedEvent -> onBatchFailed(event)

                is BatchEvent.TreeSigningStartedEvent -> {
                    signerSession = onTreeSigningStarted(event)
                }

                is BatchEvent.TreeNoncesAggregatedEvent -> {
                    onTreeNoncesAggregated(event)
                }

                is BatchEvent.TreeTxEvent -> {
                    onTreeTx(event)
                }

                is BatchEvent.TreeSignatureEvent -> {
                    onTreeSignature(event)
                }

                is BatchEvent.TreeNoncesEvent -> {
                    onTreeNonces(event)
                }

                is BatchEvent.HeartbeatEvent -> {}
            }
            return false
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * Builds and signs the forfeit/commitment transactions required to finalize the batch, then
     * submits them via [client].
     *
     * For each input in [inputs] that [ArkCoin.requiresForfeit], this validates [connectors]
     * (when present) into a [TxTree], pairs the coin with the next available connector leaf,
     * builds a forfeit transaction via [ArkTransactionBuilder.constructForfeitTx], signs it with
     * [wallet], and Base64-encodes it. For boarding inputs (`isUnrolled`), the commitment PSBT's
     * witness data is updated and signed by [wallet] for each corresponding input.
     *
     * If any forfeit transaction was signed or the commitment transaction was signed, both are
     * submitted to the server via [ArkadeClient.submitForfeitTxs].
     *
     * @param event The finalization event carrying the unsigned commitment transaction.
     * @param connectors The connector tree nodes to use for funding forfeit transaction inputs.
     * @throws IllegalStateException if the commitment tx cannot be read, a connector leaf has
     * no outputs, or updating a boarding input's witness data fails.
     * @throws IllegalArgumentException if a forfeit-requiring coin has no connector available.
     */
    override suspend fun onBatchFinalization(
        event: BatchEvent.BatchFinalizationEvent,
        connectors: List<TxTreeNode>,
    ) {
        val commitmentPSBT =
            Psbt
                .read(event.commitmentTx.encodeToByteArray())
                .getOrElse { throw IllegalStateException("Failed to read commitment tx") }
        var connectorsGraph: TxTree? = null
        if (connectors.isNotEmpty()) {
            connectorsGraph = TxTree.create(connectors)
            TreeValidator.validateConnectorsTxGraph(commitmentPSBT, connectorsGraph)
        }

        val signedForfeitTxs: MutableList<String> = mutableListOf()

        val connectorsLeaves = connectorsGraph?.leaves()?.toList() ?: listOf()
        var connectorIndex = 0

        for (vtxoCoin in inputs) {
            if (!vtxoCoin.requiresForfeit()) {
                continue
            }

            require(connectorsLeaves.isNotEmpty()) { "Connectors not received from operator" }

            require(connectorIndex < connectorsLeaves.size) {
                "Not enough connectors received. Need at least ${connectorIndex + 1}, got ${connectorsLeaves.size}"
            }

            val connectorLeaf = connectorsLeaves[connectorIndex]
            val connectorOutput =
                connectorLeaf.global.tx.txOut
                    .firstOrNull()
            if (connectorOutput == null) {
                throw IllegalStateException("Connector leaf at index $connectorIndex has no outputs")
            }

            val connectorTxId = connectorLeaf.global.tx.txid

            connectorIndex++

            val forfeitTx =
                ArkTransactionBuilder.constructForfeitTx(
                    coin = vtxoCoin,
                    connector = connectorOutput,
                    connectorTxId = connectorTxId,
                    forfeitDestination = serverInfo.forfeitAddress,
                )

            val signerDescriptor = requireNotNull(vtxoCoin.signerDescriptor) { "Missing VTXO coin signer descriptor" }
            val signedForfeitTx = wallet.sign(signerDescriptor, forfeitTx, arrayOf(0))
            val signedForfeitTxBytes = Transaction.write(signedForfeitTx)
            signedForfeitTxs.add(Base64.encode(signedForfeitTxBytes))
        }

        var signedCommitmentPSBT: Psbt? = null
        val boardingCoins = inputs.filter { it.isUnrolled }
        if (boardingCoins.isNotEmpty()) {
            signedCommitmentPSBT = commitmentPSBT
            for (boardingCoin in boardingCoins) {
                val outpoint = boardingCoin.outpoint
                val boardingInput = commitmentPSBT.getInput(outpoint)
                requireNotNull(boardingInput) { "Boarding input $outpoint not found in commitment tx" }

                signedCommitmentPSBT =
                    signedCommitmentPSBT
                        ?.updateWitnessInput(
                            outpoint,
                            boardingCoin.txOut,
                        )?.getOrElse { throw IllegalStateException("Failed to update boarding input witness") }

                val signerDescriptor = requireNotNull(boardingCoin.signerDescriptor) { "Missing boarding coin signer descriptor" }
                signedCommitmentPSBT =
                    Psbt(wallet.sign(signerDescriptor, signedCommitmentPSBT!!, arrayOf(outpoint)))
            }
        }

        val signedCommitmentTx = signedCommitmentPSBT?.global?.tx

        if (signedForfeitTxs.isNotEmpty() || signedCommitmentTx != null) {
            val signedCommitmentTxBase64 =
                if (signedCommitmentTx != null) {
                    val signedCommitmentTxBytes = Transaction.write(signedCommitmentTx)
                    Base64.encode(signedCommitmentTxBytes)
                } else {
                    null
                }

            client.submitForfeitTxs(signedForfeitTxs, signedCommitmentTxBase64)
        }
    }

    override suspend fun onBatchFailed(event: BatchEvent.BatchFailedEvent) {
        if (event.id == batchId) {
            isComplete = true
            throw UnsupportedOperationException("Batch failed: ${event.reason}")
        }
    }

    override suspend fun onTreeSigningStarted(event: BatchEvent.TreeSigningStartedEvent): TreeSignerSession {
        val vtxoGraph = TxTree.create(vtxos)
        val commitmentTx =
            Psbt.read(event.unsignedCommitmentTx.encodeToByteArray()).getOrElse {
                throw UnsupportedOperationException("Failed to read commitment tx")
            }

        TreeValidator.validateVtxoTxGraph(vtxoGraph, commitmentTx, sweepTapTree.hash())

        validateIntentOutputs(vtxoGraph, commitmentTx)

        val sharedOutput = commitmentTx.global.tx.txOut[0]

        val signerSession =
            TreeSignerSession(
                wallet,
                vtxoGraph,
                signerDescriptor!!,
                sweepTapTree,
                sharedOutput.amount.sat,
            )

        val nonces =
            signerSession.getNonces().entries.associate { entry ->
                entry.key.toHex() to entry.value.data.toHex()
            }

        val signer = wallet.signer

        val signerPubKey = signer.xOnlyPublicKey(signerDescriptor)

        Log.info(
            LOG_TAG,
            "SubmitTreeNonces: using signerPubKey=$signerPubKey" +
                " (descriptorPubKey would have been ${signerDescriptor.toXOnlyPubKey()})",
        )

        client.submitTreeNonces(
            event.id,
            signerPubKey.value.toHex(),
            nonces,
        )
        return signerSession
    }

    override suspend fun onTreeNoncesAggregated(event: BatchEvent.TreeNoncesAggregatedEvent) {
        if (signerSession != null && signerDescriptor != null) {
            val treeNonces =
                event.treeNonces.entries.associate { entry ->
                    val pubNonce = IndividualNonce(ByteVector.fromHex(entry.value))
                    val txId = ByteVector32.fromValidHex(entry.key)
                    txId to pubNonce
                }
            signerSession?.verifyAggregatedNonces(treeNonces)

            val signatures =
                signerSession?.sign()?.entries?.associate { entry ->
                    entry.key.toHex() to entry.value.toHex()
                }!!

            val signerPubKey =
                wallet.signer
                    .xOnlyPublicKey(signerDescriptor)
                    .value
                    .toHex()

            Log.info(LOG_TAG, "SubmitTreeSignatures: using signerPubKey=$signerPubKey")

            client.submitTreeSignatures(batchId, signerPubKey, signatures)
        }
    }

    override suspend fun onTreeTx(event: BatchEvent.TreeTxEvent) {
        val children =
            event.children.entries.associate { child ->
                child.key.toLong() to TxId(child.value)
            }
        val psbt = Psbt.read(event.tx.encodeToByteArray()).getOrElse { throw IllegalStateException("Failed to read tx") }
        val txNode = TxTreeNode(psbt, children)
        when (event.batchIndex) {
            0 -> vtxos.add(txNode)
            1 -> connectors.add(txNode)
        }
    }

    override suspend fun onTreeNonces(event: BatchEvent.TreeNoncesEvent) {
        if (signerSession != null) {
            val treeNonces =
                event.treeNonces.map { nonce ->
                    IndividualNonce(ByteVector.fromHex(nonce.value))
                }
            val txId = TxId(event.txId)
            signerSession?.aggregateNonces(treeNonces, txId)
        }
    }

    private fun parseIntentOutputs(): List<TxOut> {
        val registerProof =
            Psbt
                .read(intent.registerProof.encodeToByteArray())
                .getOrElse { throw IllegalStateException("Failed to read intent register proof") }
        return registerProof.global.tx.txOut
    }

    private fun validateIntentOutputs(
        vtxoGraph: TxTree,
        commitmentTx: Psbt,
    ) {
        val intentOutputs = parseIntentOutputs()
        if (intentOutputs.isEmpty()) return

        val onChainIndexes = intentParameters.onChainOutputsIndexes.toHashSet()
        val vtxoLeaves = vtxoGraph.leaves()
        val vtxoLeafOutputs =
            vtxoLeaves.flatMap { leaf ->
                leaf.global.tx.txOut.mapIndexed { _, output ->
                    output
                }
            }

        var intentAssetPacket: Packet? = null

        intentOutputs.forEachIndexed { index, intentOutput ->
            if (intentOutput.isUnSpendable()) {
                val scriptPubKey = intentOutput.publicKeyScript.toByteArray()
                if (isExtension(scriptPubKey)) {
                    intentAssetPacket = Extension.fromScript(scriptPubKey).getAssetPacket()
                }
                return@forEachIndexed
            }

            val isOnChain = onChainIndexes.contains(index)

            if (isOnChain) {
                val isInCommitmentTx =
                    commitmentTx.global.tx.txOut.any { output ->
                        output.publicKeyScript == intentOutput.publicKeyScript &&
                            output.amount == intentOutput.amount
                    }
                if (!isInCommitmentTx) {
                    throw UnsupportedOperationException(
                        "Onchain output $index not found in commitment transaction. Expected: ${intentOutput.amount} sats to ${intentOutput.publicKeyScript}",
                    )
                }
            } else {
                val isInVtxoLeaves =
                    vtxoLeafOutputs.any { output ->
                        output.publicKeyScript == intentOutput.publicKeyScript &&
                            output.amount == intentOutput.amount
                    }
                if (!isInVtxoLeaves) {
                    throw UnsupportedOperationException(
                        "Offchain output $index not found in VTXO tree leaves. Expected: ${intentOutput.amount} sats to ${intentOutput.publicKeyScript}",
                    )
                }
            }
        }

        if (intentAssetPacket != null) {
            validateLeafAssetPacket(intentAssetPacket, vtxoLeaves)
        }
    }

    private fun validateLeafAssetPacket(
        intentPacket: Packet,
        vtxoLeaves: Iterable<Psbt>,
    ) {
        vtxoLeaves.forEach { leaf ->
            val leafTx = leaf.global.tx
            leafTx.txOut.forEach { output ->
                val scriptPubKey = output.publicKeyScript.toByteArray()
                if (!output.isUnSpendable()) return@forEach
                if (!isExtension(scriptPubKey)) return@forEach

                val leafPacket =
                    Extension.fromScript(scriptPubKey).getAssetPacket() ?: return@forEach
                if (assetPacketOutputMatch(intentPacket, leafPacket)) return
            }
        }
        throw UnsupportedOperationException("Intent asset packet not found in VTXO tree leaves")
    }

    private fun assetPacketOutputMatch(
        intentPacket: Packet,
        leafPacket: Packet,
    ): Boolean {
        if (intentPacket.groups.size != leafPacket.groups.size) return false

        intentPacket.groups.forEachIndexed { index, intentAssetGroup ->
            val leafAssetGroup = leafPacket.groups[index]

            if (intentAssetGroup.assetId.toString() != leafAssetGroup.assetId.toString()) return false

            if (intentAssetGroup.outputs.size != leafAssetGroup.outputs.size) return false

            intentAssetGroup.outputs.forEachIndexed { index, intentAssetOutput ->
                val leafAssetOutput = leafAssetGroup.outputs[index]
                if (
                    intentAssetOutput.vout != leafAssetOutput.vout ||
                    intentAssetOutput.amount != leafAssetOutput.amount
                ) {
                    return false
                }
            }
        }
        return true
    }

    companion object {
        private const val LOG_TAG = "BatchSession"
    }
}
