package com.arkade.core.contracts

import com.arkade.core.coins.ArkCoin
import com.arkade.core.script.HashLockTapScript
import com.arkade.core.vtxos.ScriptSpendingPath
import com.arkade.core.vtxos.Vtxo
import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.readNBytes

class ArkNoteContract(
    walletId: String,
    val amount: Int,
    val preimage: ByteArray,
) : ArkContract(walletId, null) {
    override val type = TYPE

    override val defaultScope: ContractScope = ContractScope.OFF_CHAIN

    val hash = sha256(preimage)
    val outpoint = OutPoint(TxId(hash), 0)

    override fun getTapLeafScripts(): List<ByteArray> = listOf(claimScript())

    override fun getAdditionalData(): Map<String, String> =
        mapOf(
            "preimage" to preimage.toHexString(),
            "amount" to amount.toString(),
        )

    override suspend fun toArkCoin(vtxo: Vtxo.Data): ArkCoin =
        ArkCoin(
            walletId,
            this,
            vtxo.createdAt,
            vtxo.expiresAt,
            vtxo.expiresAtHeight,
            outpoint,
            vtxo.txOut,
            null,
            claimPath(),
            ScriptWitness(listOf(ByteVector(preimage))),
            null,
            null,
            true,
            vtxo.isUnrolled,
            vtxo.assets,
        )

    fun claimPath(): ScriptSpendingPath {
        val script = claimScript()
        val controlBlock = getControlBlock(script)
        return ScriptSpendingPath(script, controlBlock)
    }

    private fun claimScript(): ByteArray {
        val hashLock = HashLockTapScript(hash, HashLockTapScript.HashLockType.SHA256)
        return hashLock.buildScript()
    }

    companion object {
        const val TYPE = "arknote"

        private const val PREIMAGE_SIZE = 32
        private const val AMOUNT_LENGTH = 4

        fun parse(
            walletId: String,
            data: Map<String, String>,
        ): ArkContract {
            val amount = data["amount"]?.toInt()
            val preimage = data["preimage"]?.encodeToByteArray()
            requireNotNull(amount) { "Invalid contract amount" }
            requireNotNull(preimage) { "Invalid contract preimage" }
            return ArkNoteContract(walletId, amount, preimage)
        }

        fun parse(
            walletId: String,
            note: String,
        ): ArkContract {
            require(note.startsWith(TYPE)) { "Invalid Ark note" }
            val contractData = note.substring(TYPE.length)
            val contractBytes = Base58.decode(contractData)
            require(contractBytes.size == PREIMAGE_SIZE + AMOUNT_LENGTH) {
                "Invalid Ark note"
            }
            val contractBytesInput = ByteArrayInput(contractBytes)
            val preimage = contractBytesInput.readNBytes(PREIMAGE_SIZE)
            val amount = contractBytesInput.readNBytes(AMOUNT_LENGTH)?.toHexString()?.toInt()
            requireNotNull(amount) { "Invalid contract amount" }
            requireNotNull(preimage) { "Invalid contract preimage" }
            return ArkNoteContract(walletId, amount, preimage)
        }
    }
}
