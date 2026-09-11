package com.arkade.core.coins

import com.arkade.core.assets.Asset
import com.arkade.core.contracts.ArkContract
import com.arkade.core.vtxos.ScriptSpendingPath
import fr.acinq.bitcoin.OP_CHECKSEQUENCEVERIFY
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.TxOut

data class ArkCoin(
    val walletId: String,
    val contract: ArkContract,
    val createdAt: Long,
    val expiresAt: Long,
    val expiresAtHeight: Long,
    val outpoint: OutPoint,
    val txOut: TxOut,
    val signerDescriptor: String?,
    val spendingScriptPath: ScriptSpendingPath,
    val spendingConditionWitness: ScriptWitness?,
    val lockTime: Long?,
    val sequence: Long?,
    val isSwept: Boolean,
    val isUnrolled: Boolean,
    val assets: List<Asset>?,
) {
    init {
        val script = Script.parse(spendingScriptPath.script)
        if (sequence == null && script.contains(OP_CHECKSEQUENCEVERIFY)) {
            throw IllegalArgumentException("Sequence is required")
        }
    }

    /**
     * Whether this coin must be spent by a forfeit transaction during batch finalization.
     *
     * This is the case for any coin that is neither being swept (`isSwept`) nor a boarding
     * coin being unrolled into the batch (`isUnrolled`).
     */
    fun requiresForfeit(): Boolean = !isSwept && !isUnrolled
}
