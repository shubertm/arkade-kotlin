package com.arkade.core.contracts

import com.arkade.core.coins.ArkCoin
import com.arkade.core.csvSigScript
import com.arkade.core.multisigScript
import com.arkade.core.taproot.pubKeyFromTaprootDescriptor
import com.arkade.core.toXOnlyPubKey
import com.arkade.core.vtxos.ScriptSpendingPath
import com.arkade.core.vtxos.Vtxo

class ArkPaymentContract(
    walletId: String,
    serverDescriptor: String,
    private val userDescriptor: String,
    private val exitDelay: Long,
) : ArkContract(walletId, serverDescriptor) {
    override val type: String = TYPE

    override val defaultScope: ContractScope = ContractScope.OFF_CHAIN

    override fun getTapLeafScripts(): List<ByteArray> {
        requireNotNull(serverDescriptor) { "Invalid signer descriptor" }
        val serverPubKey = pubKeyFromTaprootDescriptor(serverDescriptor).toXOnlyPubKey()
        val userPubKey = pubKeyFromTaprootDescriptor(userDescriptor).toXOnlyPubKey()
        val collaborativeScript = multisigScript(serverPubKey, userPubKey)
        val unilateralScript = csvSigScript(exitDelay, userPubKey)
        return listOf(collaborativeScript, unilateralScript)
    }

    override fun getAdditionalData(): Map<String, String> {
        requireNotNull(serverDescriptor) { "Invalid signer descriptor" }
        return mapOf(
            "server" to serverDescriptor,
            "user" to userDescriptor,
            "exit_delay" to exitDelay.toString(),
        )
    }

    override suspend fun toArkCoin(vtxo: Vtxo.Data): ArkCoin =
        ArkCoin(
            walletId,
            contract = this,
            createdAt = vtxo.createdAt,
            expiresAt = vtxo.expiresAt,
            expiresAtHeight = vtxo.expiresAtHeight,
            outpoint = vtxo.outpoint,
            txOut = vtxo.txOut,
            signerDescriptor = userDescriptor,
            spendingScriptPath = collaborativePath(),
            spendingConditionWitness = null,
            lockTime = null,
            sequence = null,
            isSwept = vtxo.isSwept,
            isUnrolled = vtxo.isUnrolled,
            assets = vtxo.assets,
        )

    private fun collaborativePath(): ScriptSpendingPath {
        val scripts = getTapLeafScripts()
        val collaborativeScript = scripts[0]
        val controlBlock = getControlBlock(collaborativeScript)
        return ScriptSpendingPath(
            collaborativeScript,
            controlBlock,
        )
    }

    private fun unilateralPath(): ScriptSpendingPath {
        val scripts = getTapLeafScripts()
        val unilateralScript = scripts[1]
        val controlBlock = getControlBlock(unilateralScript)
        return ScriptSpendingPath(
            unilateralScript,
            controlBlock,
        )
    }

    companion object {
        const val TYPE = "Payment"

        fun parse(
            walletId: String,
            data: Map<String, String>,
        ): ArkContract {
            val serverPubKeyDescriptor = data["server"]
            val userPubKeyDescriptor = data["user"]
            val exitDelay = data["exit_delay"]?.toLong() ?: 0
            requireNotNull(serverPubKeyDescriptor) { "Invalid server public key" }
            requireNotNull(userPubKeyDescriptor) { "Invalid user public key" }
            require(exitDelay >= 0) { "Invalid exit delay" }
            require(serverPubKeyDescriptor.isNotBlank()) { "Invalid server public key" }
            require(userPubKeyDescriptor.isNotBlank()) { "Invalid user public key" }

            return ArkPaymentContract(
                walletId,
                serverPubKeyDescriptor,
                userPubKeyDescriptor,
                exitDelay,
            )
        }
    }
}
