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

    /**
     * Returns the collaborative, unilateral-exit, and delegated spending scripts, in that order.
     */
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

    /**
     * Returns the descriptors and delays needed to reconstruct this contract.
     *
     * The `cltv_locktime` entry is omitted when no absolute delegate lock time is configured.
     */
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

    /** Converts [vtxo] to a user-signed coin that uses the collaborative spending path. */
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

    /** Returns the collaborative leaf and its control block as a spending path. */
    fun collaborativePath(): ScriptSpendingPath {
        val scripts = getTapLeafScripts()
        val collaborativeScript = scripts[0]
        val controlBlock = getControlBlock(collaborativeScript)
        return ScriptSpendingPath(collaborativeScript, controlBlock)
    }

    /** Builds the leaf requiring both the user and server signatures. */
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

    /** Builds the user-only leaf gated by the relative [exitDelay]. */
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

    /**
     * Builds the server, user, and delegate leaf, gated by [cltvLockTime] when configured.
     */
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

        /**
         * Reconstructs a delegate contract from its serialized fields.
         *
         * @throws IllegalArgumentException If a required descriptor or delay is missing, or a
         * delay is not a valid `Long` value.
         */
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
