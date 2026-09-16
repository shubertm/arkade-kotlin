package com.arkade.core.contracts

import com.arkade.core.bitcoin.Blockchain
import com.arkade.core.bitcoin.isTimeLock
import com.arkade.core.coins.ArkCoin
import com.arkade.core.script.CollaborativePathArkTapScript
import com.arkade.core.script.CompositeTapScript
import com.arkade.core.script.HashLockTapScript
import com.arkade.core.script.LockTimeTapScript
import com.arkade.core.script.NofNMultisigTapScript
import com.arkade.core.script.UnilateralPathArkTapScript
import com.arkade.core.script.VerifyTapScript
import com.arkade.core.taproot.pubKeyFromTaprootDescriptor
import com.arkade.core.toXOnlyPubKey
import com.arkade.core.vtxos.ScriptSpendingPath
import com.arkade.core.vtxos.Vtxo
import com.arkade.utils.Log
import com.arkade.utils.info
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Crypto.hash160
import fr.acinq.bitcoin.ScriptWitness

class VHTLCContract(
    walletId: String,
    serverDescriptor: String,
    private val senderDescriptor: String,
    private val receiverDescriptor: String,
    private val hash: ByteArray,
    private val refundLockTime: Long,
    private val unilateralClaimDelay: Long,
    private val unilateralRefundDelay: Long,
    private val unilateralRefundWithoutReceiverDelay: Long,
) : ArkContract(walletId, serverDescriptor) {
    constructor(
        walletId: String,
        serverDescriptor: String,
        senderDescriptor: String,
        receiverDescriptor: String,
        preimage: ByteVector,
        refundLockTime: Long,
        unilateralClaimDelay: Long,
        unilateralRefundDelay: Long,
        unilateralRefundWithoutReceiverDelay: Long,
    ) : this(
        walletId,
        serverDescriptor,
        senderDescriptor,
        receiverDescriptor,
        hash160(preimage),
        refundLockTime,
        unilateralClaimDelay,
        unilateralRefundDelay,
        unilateralRefundWithoutReceiverDelay,
    ) {
        this.preimage = preimage.toByteArray()
    }

    var preimage: ByteArray? = null
        private set

    var chainTimeProvider: Blockchain? = null
        private set

    override val type: String = TYPE

    override val defaultScope: ContractScope = ContractScope.OFF_CHAIN

    /**
     * Returns all supported leaves in claim, cooperative refund, absolute refund, unilateral
     * claim, unilateral refund, and sender-only unilateral refund order.
     */
    override fun getTapLeafScripts(): List<ByteArray> {
        val claimScript = claimScript()
        val cooperativeScript = cooperativeScript()
        val refundWithoutReceiverScript = refundWithoutReceiverScript()
        val unilateralClaimScript = unilateralClaimScript()
        val unilateralRefundScript = unilateralRefundScript()
        val unilateralRefundWithoutReceiverScript = unilateralRefundWithoutReceiverScript()

        return listOf(
            claimScript,
            cooperativeScript,
            refundWithoutReceiverScript,
            unilateralClaimScript,
            unilateralRefundScript,
            unilateralRefundWithoutReceiverScript,
        )
    }

    /**
     * Returns the descriptors, hash, absolute refund lock, and unilateral delays for this contract.
     *
     * The hex-encoded preimage is included only when this instance was constructed or parsed with
     * one.
     */
    override fun getAdditionalData(): Map<String, String> {
        val data =
            mutableMapOf(
                "server" to serverDescriptor.toString(),
                "sender" to senderDescriptor,
                "receiver" to receiverDescriptor,
                "hash" to hash.toHexString(),
                "refundLockTime" to refundLockTime.toString(),
                "unilateralClaimDelay" to unilateralClaimDelay.toString(),
                "unilateralRefundDelay" to unilateralRefundDelay.toString(),
                "unilateralRefundWithoutReceiverDelay" to unilateralRefundWithoutReceiverDelay.toString(),
            )
        if (preimage != null) {
            data["preimage"] = preimage!!.toHexString()
        }
        return data
    }

    /**
     * Selects a claim or sender-only refund coin for [vtxo].
     *
     * When a preimage is available, the returned coin uses the receiver descriptor, claim path,
     * and preimage witness. Otherwise, this uses [chainTimeProvider] to select the sender-only
     * refund path once [refundLockTime] has passed: timestamp locks require the current chain time
     * to be strictly later, while height locks allow the matching height.
     *
     * @throws IllegalArgumentException If a chain-time provider is required but has not been set.
     * @throws UnsupportedOperationException If no preimage is available and the refund lock has
     * not elapsed.
     */
    override suspend fun toArkCoin(vtxo: Vtxo.Data): ArkCoin {
        if (preimage != null) {
            Log.info(
                LOG_TAG,
                "VHTLC claim: wallet=$walletId, receiver=$receiverDescriptor, sender=$senderDescriptor, outpoint=${vtxo.outpoint}",
            )
            val witness = ScriptWitness(listOf(ByteVector(preimage!!)))
            return ArkCoin(
                walletId,
                this,
                vtxo.createdAt,
                vtxo.expiresAt,
                vtxo.expiresAtHeight,
                vtxo.outpoint,
                vtxo.txOut,
                receiverDescriptor,
                claimPath(),
                witness,
                null,
                null,
                vtxo.isSwept,
                vtxo.isUnrolled,
                vtxo.assets,
            )
        }

        val chainTime = requireNotNull(chainTimeProvider) { "Missing chain time provider" }.getChainTime()
        val refundElapsed =
            if (refundLockTime.isTimeLock()) {
                refundLockTime < chainTime.time
            } else {
                chainTime.height >= refundLockTime
            }

        if (refundElapsed) {
            Log.info(
                LOG_TAG,
                "VHTLC refund: wallet=$walletId, sender=$senderDescriptor, receiver=$receiverDescriptor, outpoint=${vtxo.outpoint}, refundLockTime=$refundLockTime",
            )
            return ArkCoin(
                walletId,
                this,
                vtxo.createdAt,
                vtxo.expiresAt,
                vtxo.expiresAtHeight,
                vtxo.outpoint,
                vtxo.txOut,
                senderDescriptor,
                refundWithoutReceiverPath(),
                null,
                refundLockTime,
                null,
                vtxo.isSwept,
                vtxo.isUnrolled,
                vtxo.assets,
            )
        }

        throw UnsupportedOperationException("Cannot transform contract in coin")
    }

    /**
     * Converts an unspent [vtxo] to a sender-signed coin using the cooperative refund path.
     *
     * @throws IllegalStateException If [vtxo] is already spent.
     */
    fun toCoopRefundCoin(vtxo: Vtxo.Data): ArkCoin {
        if (vtxo.isSpent) {
            throw IllegalStateException("VTXO is already spent")
        }
        return ArkCoin(
            walletId,
            this,
            vtxo.createdAt,
            vtxo.expiresAt,
            vtxo.expiresAtHeight,
            vtxo.outpoint,
            vtxo.txOut,
            senderDescriptor,
            cooperativePath(),
            null,
            null,
            null,
            vtxo.isSwept,
            vtxo.isUnrolled,
            vtxo.assets,
        )
    }

    /** Returns the preimage claim leaf and its control block as a spending path. */
    fun claimPath(): ScriptSpendingPath {
        val scripts = getTapLeafScripts()
        val claimScript = scripts[0]
        val controlBlock = getControlBlock(claimScript)
        return ScriptSpendingPath(claimScript, controlBlock)
    }

    /** Returns the cooperative refund leaf and its control block as a spending path. */
    fun cooperativePath(): ScriptSpendingPath {
        val scripts = getTapLeafScripts()
        val cooperativeScript = scripts[1]
        val controlBlock = getControlBlock(cooperativeScript)
        return ScriptSpendingPath(cooperativeScript, controlBlock)
    }

    /** Returns the absolute sender-only refund leaf and its control block as a spending path. */
    fun refundWithoutReceiverPath(): ScriptSpendingPath {
        val script = getTapLeafScripts()[2]
        val controlBlock = getControlBlock(script)
        return ScriptSpendingPath(script, controlBlock)
    }

    /** Sets the blockchain provider used to decide whether a refund lock has elapsed. */
    fun setChainTimeProvider(provider: Blockchain) {
        chainTimeProvider = provider
    }

    /** Builds the hash-locked leaf requiring the preimage plus receiver and server signatures. */
    private fun claimScript(): ByteArray {
        val hashLockScriptBytes = HashLockTapScript(hash).buildScript()

        val verifyScriptBytes = VerifyTapScript().buildScript()

        val owners = listOf(pubKeyFromTaprootDescriptor(receiverDescriptor).toXOnlyPubKey())
        val receiverMultisigScriptBytes = NofNMultisigTapScript(owners).buildScript()

        val scripts = listOf(hashLockScriptBytes, verifyScriptBytes, receiverMultisigScriptBytes)
        val compositeScript = CompositeTapScript(scripts)

        requireNotNull(serverDescriptor) { "Invalid server descriptor" }
        val serverPubKey = pubKeyFromTaprootDescriptor(serverDescriptor).toXOnlyPubKey()
        val collaborativeScript = CollaborativePathArkTapScript(serverPubKey, compositeScript)
        return collaborativeScript.buildScript()
    }

    /** Builds the leaf requiring sender, receiver, and server signatures. */
    private fun cooperativeScript(): ByteArray {
        val owners =
            listOf(
                pubKeyFromTaprootDescriptor(senderDescriptor).toXOnlyPubKey(),
                pubKeyFromTaprootDescriptor(receiverDescriptor).toXOnlyPubKey(),
            )
        val multisigScript = NofNMultisigTapScript(owners)

        requireNotNull(serverDescriptor) { "Invalid server descriptor" }
        val collaborativeScript =
            CollaborativePathArkTapScript(
                pubKeyFromTaprootDescriptor(serverDescriptor).toXOnlyPubKey(),
                multisigScript,
            )
        return collaborativeScript.buildScript()
    }

    /** Builds the absolute-lock refund leaf requiring sender and server signatures. */
    private fun refundWithoutReceiverScript(): ByteArray {
        val owners = listOf(pubKeyFromTaprootDescriptor(senderDescriptor).toXOnlyPubKey())
        val multisigScript = NofNMultisigTapScript(owners).buildScript()

        val lockTimeScript = LockTimeTapScript(refundLockTime).buildScript()

        val scripts = listOf(lockTimeScript, multisigScript)
        val compositeScript = CompositeTapScript(scripts)

        requireNotNull(serverDescriptor) { "Invalid server descriptor" }
        val collaborativeScript =
            CollaborativePathArkTapScript(
                pubKeyFromTaprootDescriptor(serverDescriptor).toXOnlyPubKey(),
                compositeScript,
            )
        return collaborativeScript.buildScript()
    }

    /** Builds the delayed preimage claim leaf requiring the receiver signature. */
    private fun unilateralClaimScript(): ByteArray {
        val hashLockScript = HashLockTapScript(hash)
        val receiverMultisigScript =
            NofNMultisigTapScript(
                listOf(pubKeyFromTaprootDescriptor(receiverDescriptor).toXOnlyPubKey()),
            )
        val unilateralClaimScript =
            UnilateralPathArkTapScript(
                unilateralClaimDelay,
                receiverMultisigScript,
                hashLockScript,
            )
        return unilateralClaimScript.buildScript()
    }

    /** Builds the delayed refund leaf requiring both sender and receiver signatures. */
    private fun unilateralRefundScript(): ByteArray {
        val unilateralClaimScript =
            UnilateralPathArkTapScript(
                unilateralRefundDelay,
                NofNMultisigTapScript(
                    listOf(
                        pubKeyFromTaprootDescriptor(senderDescriptor).toXOnlyPubKey(),
                        pubKeyFromTaprootDescriptor(receiverDescriptor).toXOnlyPubKey(),
                    ),
                ),
            )
        return unilateralClaimScript.buildScript()
    }

    /** Builds the delayed sender-only refund leaf. */
    private fun unilateralRefundWithoutReceiverScript(): ByteArray {
        val unilateralClaimScript =
            UnilateralPathArkTapScript(
                unilateralRefundWithoutReceiverDelay,
                NofNMultisigTapScript(
                    listOf(pubKeyFromTaprootDescriptor(senderDescriptor).toXOnlyPubKey()),
                ),
            )
        return unilateralClaimScript.buildScript()
    }

    companion object {
        const val TYPE = "HTLC"
        const val LOG_TAG = "VHTLCContract"

        /**
         * Reconstructs a VHTLC from its serialized descriptors, hash, locks, and optional preimage.
         *
         * When present, the preimage is decoded from hexadecimal and must match the stored HASH160
         * digest.
         *
         * @throws IllegalArgumentException If a required field is missing, a lock is not a valid
         * `Long`, hexadecimal data is invalid, or the preimage does not match the hash.
         */
        fun parse(
            walletId: String,
            data: Map<String, String>,
        ): ArkContract {
            val serverDescriptor = data["server"]
            requireNotNull(serverDescriptor) { "Missing server descriptor" }
            val senderDescriptor = data["sender"]
            requireNotNull(senderDescriptor) { "Missing sender descriptor" }
            val receiverDescriptor = data["receiver"]
            requireNotNull(receiverDescriptor) { "Missing receiver descriptor" }
            val hash = requireNotNull(data["hash"]) { "Missing hash" }.hexToByteArray()
            val refundLockTime = data["refundLockTime"]
            requireNotNull(refundLockTime) { "Missing refund lock time" }
            val unilateralClaimDelay = data["unilateralClaimDelay"]
            requireNotNull(unilateralClaimDelay) { "Missing unilateral claim delay" }
            val unilateralRefundDelay = data["unilateralRefundDelay"]
            requireNotNull(unilateralRefundDelay) { "Missing unilateral refund delay" }
            val unilateralRefundWithoutReceiverDelay = data["unilateralRefundWithoutReceiverDelay"]
            requireNotNull(unilateralRefundWithoutReceiverDelay) { "Missing unilateral refund without receiver delay" }
            val preimage = data["preimage"]
            if (preimage != null) {
                val preimageBytes = ByteVector.fromHex(preimage)
                require(hash.contentEquals(hash160(preimageBytes))) { "preimage does not match hash" }
                return VHTLCContract(
                    walletId,
                    serverDescriptor,
                    senderDescriptor,
                    receiverDescriptor,
                    preimageBytes,
                    refundLockTime.toLong(),
                    unilateralClaimDelay.toLong(),
                    unilateralRefundDelay.toLong(),
                    unilateralRefundWithoutReceiverDelay.toLong(),
                )
            }
            return VHTLCContract(
                walletId,
                serverDescriptor,
                senderDescriptor,
                receiverDescriptor,
                hash,
                refundLockTime.toLong(),
                unilateralClaimDelay.toLong(),
                unilateralRefundDelay.toLong(),
                unilateralRefundWithoutReceiverDelay.toLong(),
            )
        }
    }
}
