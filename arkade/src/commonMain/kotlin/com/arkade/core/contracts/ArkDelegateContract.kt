package com.arkade.core.contracts

import com.arkade.core.coins.ArkCoin
import com.arkade.core.script.CollaborativePathArkTapScript
import com.arkade.core.script.CompositeTapScript
import com.arkade.core.script.LockTimeTapScript
import com.arkade.core.script.NofNMultisigTapScript
import com.arkade.core.script.UnilateralPathArkTapScript
import com.arkade.core.taproot.pubKeyFromTaprootDescriptor
import com.arkade.core.toXOnlyPubKey
import com.arkade.core.vtxos.ScriptSpendingPath
import com.arkade.core.vtxos.Vtxo

class ArkDelegateContract(
    walletId: String,
    serverDescriptor: String,
    private val userDescriptor: String,
    private val exitDelay: Long,
    private val delegateDescriptor: String,
    private val cltvLockTime: Long? = null,
) : ArkContract(walletId, serverDescriptor) {
    override val type: String = TYPE

    override val defaultScope: ContractScope = ContractScope.OFF_CHAIN

    override fun getTapLeafScripts(): List<ByteArray> {
        val collaborativeScript = collaborativeScript()
        val exitScript = exitScript()
        val delegateScript = delegateScript()
        return listOf(
            collaborativeScript,
            exitScript,
            delegateScript,
        )
    }

    override fun getAdditionalData(): Map<String, String> {
        val data =
            mutableMapOf(
                "exit_delay" to exitDelay.toString(),
                "user" to userDescriptor,
                "delegate" to delegateDescriptor,
                "server" to serverDescriptor.toString(),
            )
        if (cltvLockTime != null) {
            data["cltv_locktime"] = cltvLockTime.toString()
        }
        return data
    }

    override suspend fun toArkCoin(vtxo: Vtxo.Data): ArkCoin =
        ArkCoin(
            walletId,
            this,
            vtxo.createdAt,
            vtxo.expiresAt,
            vtxo.expiresAtHeight,
            vtxo.outpoint,
            vtxo.txOut,
            userDescriptor,
            collaborativePath(),
            null,
            null,
            null,
            vtxo.isSwept,
            vtxo.isUnrolled,
            vtxo.assets,
        )

    fun collaborativePath(): ScriptSpendingPath {
        val scripts = getTapLeafScripts()
        val collaborativeScript = scripts[0]
        val controlBlock = getControlBlock(collaborativeScript)
        return ScriptSpendingPath(collaborativeScript, controlBlock)
    }

    private fun collaborativeScript(): ByteArray {
        val ownerScript =
            NofNMultisigTapScript(
                listOf(
                    pubKeyFromTaprootDescriptor(userDescriptor).toXOnlyPubKey(),
                ),
            )
        requireNotNull(serverDescriptor) { "Invalid server descriptor" }
        val collaborativeScript =
            CollaborativePathArkTapScript(
                pubKeyFromTaprootDescriptor(serverDescriptor).toXOnlyPubKey(),
                ownerScript,
            )
        return collaborativeScript.buildScript()
    }

    private fun exitScript(): ByteArray {
        val ownerScript =
            NofNMultisigTapScript(
                listOf(
                    pubKeyFromTaprootDescriptor(userDescriptor).toXOnlyPubKey(),
                ),
            )
        val unilateralScript = UnilateralPathArkTapScript(exitDelay, ownerScript)
        return unilateralScript.buildScript()
    }

    private fun delegateScript(): ByteArray {
        val multisigScript =
            NofNMultisigTapScript(
                listOf(
                    pubKeyFromTaprootDescriptor(userDescriptor).toXOnlyPubKey(),
                    pubKeyFromTaprootDescriptor(delegateDescriptor).toXOnlyPubKey(),
                ),
            )

        requireNotNull(serverDescriptor) { "Invalid server descriptor" }

        if (cltvLockTime != null) {
            val cltvScript = LockTimeTapScript(cltvLockTime)
            val scripts = listOf(cltvScript.buildScript(), multisigScript.buildScript())
            val compositeScript = CompositeTapScript(scripts)

            return CollaborativePathArkTapScript(
                pubKeyFromTaprootDescriptor(serverDescriptor).toXOnlyPubKey(),
                compositeScript,
            ).buildScript()
        }

        return CollaborativePathArkTapScript(
            pubKeyFromTaprootDescriptor(serverDescriptor).toXOnlyPubKey(),
            multisigScript,
        ).buildScript()
    }

    companion object {
        const val TYPE = "Delegate"

        fun parse(
            walletId: String,
            data: Map<String, String>,
        ): ArkContract {
            val serverDescriptor = data["server"]
            requireNotNull(serverDescriptor) { "Missing server descriptor" }
            val userDescriptor = data["user"]
            requireNotNull(userDescriptor) { "Missing user descriptor" }
            val delegateDescriptor = data["delegate"]
            requireNotNull(delegateDescriptor) { "Missing delegate descriptor" }
            val exitDelay = data["exit_delay"]
            requireNotNull(exitDelay) { "Missing exit delay" }
            val cltvLockTime = data["cltv_locktime"]

            return ArkDelegateContract(
                walletId,
                serverDescriptor,
                userDescriptor,
                exitDelay.toLong(),
                delegateDescriptor,
                cltvLockTime?.toLong(),
            )
        }
    }
}
