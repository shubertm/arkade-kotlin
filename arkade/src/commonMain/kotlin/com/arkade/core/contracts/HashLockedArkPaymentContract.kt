package com.arkade.core.contracts

import com.arkade.core.coins.ArkCoin
import com.arkade.core.csvSigScript
import com.arkade.core.script.CollaborativePathArkTapScript
import com.arkade.core.script.CompositeTapScript
import com.arkade.core.script.HashLockTapScript
import com.arkade.core.script.NofNMultisigTapScript
import com.arkade.core.script.VerifyTapScript
import com.arkade.core.taproot.pubKeyFromTaprootDescriptor
import com.arkade.core.toXOnlyPubKey
import com.arkade.core.vtxos.ScriptSpendingPath
import com.arkade.core.vtxos.Vtxo
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Crypto.hash160
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.ScriptWitness

class HashLockedArkPaymentContract(
    walletId: String,
    serverDescriptor: String,
    private val userDescriptor: String,
    private val exitDelay: Long,
    private val preimage: ByteArray,
    private val hashLockType: HashLockTapScript.HashLockType,
) : ArkContract(
        walletId,
        serverDescriptor,
    ) {
    override val type: String = TYPE

    override val defaultScope: ContractScope = ContractScope.OFF_CHAIN

    val hash =
        when (hashLockType) {
            HashLockTapScript.HashLockType.HASH160 -> hash160(preimage)
            HashLockTapScript.HashLockType.SHA256 -> sha256(preimage)
        }

    override fun getTapLeafScripts(): List<ByteArray> {
        val userPubKey = pubKeyFromTaprootDescriptor(userDescriptor).toXOnlyPubKey()
        val unilateralScript = csvSigScript(exitDelay, userPubKey)
        val claimScript = claimScript()

        return listOf(
            claimScript,
            unilateralScript,
        )
    }

    override fun getAdditionalData(): Map<String, String> {
        requireNotNull(serverDescriptor) { "Invalid server descriptor" }
        return mapOf(
            "server" to serverDescriptor,
            "user" to userDescriptor,
            "exit_delay" to exitDelay.toString(),
            "preimage" to preimage.toHexString(),
            "hash_lock_type" to hashLockType.name,
        )
    }

    override suspend fun toArkCoin(vtxo: Vtxo.Data): ArkCoin {
        val witness = ScriptWitness(listOf(ByteVector(preimage)))
        return ArkCoin(
            walletId,
            this,
            vtxo.createdAt,
            vtxo.expiresAt,
            vtxo.expiresAtHeight,
            vtxo.outpoint,
            vtxo.txOut,
            userDescriptor,
            claimPath(),
            witness,
            null,
            null,
            vtxo.isSwept,
            vtxo.isUnrolled,
            vtxo.assets,
        )
    }

    fun unilateralPath(): ScriptSpendingPath {
        val scripts = getTapLeafScripts()
        val unilateralScript = scripts[1]
        val controlBlock = getControlBlock(unilateralScript)
        return ScriptSpendingPath(unilateralScript, controlBlock)
    }

    fun claimPath(): ScriptSpendingPath {
        val scripts = getTapLeafScripts()
        val claimScript = scripts[0]
        val controlBlock = getControlBlock(claimScript)
        return ScriptSpendingPath(claimScript, controlBlock)
    }

    private fun claimScript(): ByteArray {
        val hashLockScriptBytes = HashLockTapScript(hash, hashLockType).buildScript()

        val owners = listOf(pubKeyFromTaprootDescriptor(userDescriptor).toXOnlyPubKey())
        val receiverMultisigScriptBytes = NofNMultisigTapScript(owners).buildScript()

        val verifyScriptBytes = VerifyTapScript().buildScript()

        requireNotNull(serverDescriptor) { "Invalid server descriptor" }
        val serverPubKey = pubKeyFromTaprootDescriptor(serverDescriptor).toXOnlyPubKey()
        val compositeScript =
            CompositeTapScript(
                listOf(
                    hashLockScriptBytes,
                    verifyScriptBytes,
                    receiverMultisigScriptBytes,
                ),
            )
        val collaborativeScript = CollaborativePathArkTapScript(serverPubKey, compositeScript)

        return collaborativeScript.buildScript()
    }

    companion object {
        const val TYPE = "HashLockPaymentContract"

        fun parse(
            walletId: String,
            data: Map<String, String>,
        ): ArkContract {
            val serverDescriptor = data["server"]
            requireNotNull(serverDescriptor) { "Missing server descriptor" }
            val userDescriptor = data["user"]
            requireNotNull(userDescriptor) { "Missing user descriptor" }
            val exitDelay = data["exit_delay"]
            requireNotNull(exitDelay) { "Missing exit delay" }
            val preimage = data["preimage"]
            requireNotNull(preimage) { "Missing preimage" }
            val hashLockType = data["hash_lock_type"]
            requireNotNull(hashLockType) { "Missing hash lock type" }

            return HashLockedArkPaymentContract(
                walletId,
                serverDescriptor,
                userDescriptor,
                exitDelay.toLong(),
                preimage.hexToByteArray(),
                HashLockTapScript.HashLockType.valueOf(hashLockType),
            )
        }
    }
}
