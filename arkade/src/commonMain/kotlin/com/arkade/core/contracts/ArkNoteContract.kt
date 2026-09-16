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
import okio.Buffer

class ArkNoteContract(
    walletId: String,
    val amount: Int,
    val preimage: ByteArray,
) : ArkContract(walletId, null) {
    override val type = TYPE

    override val defaultScope: ContractScope = ContractScope.OFF_CHAIN

    val hash = sha256(preimage)
    val outpoint = OutPoint(TxId(hash), 0)

    /** Returns the SHA-256 hash-lock leaf used to claim this note. */
    override fun getTapLeafScripts(): List<ByteArray> = listOf(claimScript())

    /** Returns the hex-encoded preimage and decimal amount used to reconstruct this note. */
    override fun getAdditionalData(): Map<String, String> =
        mapOf(
            "preimage" to preimage.toHexString(),
            "amount" to amount.toString(),
        )

    /**
     * Converts [vtxo] to a swept claim coin using this note's derived [outpoint] and preimage.
     */
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

    /** Returns the note's hash-lock leaf and its control block as a spending path. */
    fun claimPath(): ScriptSpendingPath {
        val script = claimScript()
        val controlBlock = getControlBlock(script)
        return ScriptSpendingPath(script, controlBlock)
    }

    /** Builds the SHA-256 hash-lock leaf for [preimage]. */
    private fun claimScript(): ByteArray {
        val hashLock = HashLockTapScript(hash, HashLockTapScript.HashLockType.SHA256)
        return hashLock.buildScript()
    }

    companion object {
        const val TYPE = "arknote"

        private const val PREIMAGE_SIZE = 32
        private const val AMOUNT_LENGTH = 4

        /**
         * Reconstructs a note contract from its decimal amount and hex-encoded preimage.
         *
         * @throws IllegalArgumentException If the amount or preimage is missing, the amount is
         * not an `Int`, or the preimage is invalid hexadecimal or does not decode to 32 bytes.
         */
        fun parse(
            walletId: String,
            data: Map<String, String>,
        ): ArkContract {
            val amount = data["amount"]?.toInt()
            requireNotNull(amount) { "Invalid contract amount" }

            val preimage = data["preimage"]?.hexToByteArray()
            requireNotNull(preimage) { "Invalid contract preimage" }
            require(preimage.size == PREIMAGE_SIZE) { "Invalid contract preimage" }

            return ArkNoteContract(walletId, amount, preimage)
        }

        /**
         * Decodes a Base58 Ark note containing a 32-byte preimage and four-byte signed amount.
         *
         * @throws IllegalArgumentException If [note] has the wrong prefix, an invalid Base58
         * payload, or the wrong decoded length.
         */
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
            requireNotNull(preimage) { "Invalid contract preimage" }
            require(preimage.size == PREIMAGE_SIZE) { "Invalid contract preimage" }

            val amount =
                Buffer()
                    .write(
                        requireNotNull(contractBytesInput.readNBytes(AMOUNT_LENGTH)) {
                            "Invalid Ark note amount"
                        },
                    ).readInt()

            return ArkNoteContract(walletId, amount, preimage)
        }
    }
}
