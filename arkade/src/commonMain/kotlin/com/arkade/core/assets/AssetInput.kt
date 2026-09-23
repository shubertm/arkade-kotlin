package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.readNBytes

/**
 * An input spending an existing asset amount into an [AssetGroup].
 *
 * [LOCAL] inputs reference an input of the same transaction as the containing [AssetGroup] by
 * its [vin] index. [INTENT] inputs additionally carry the [txId] of an unrelated intent
 * transaction whose output is being consumed.
 *
 * @property type Whether this input references a local transaction input or an external intent.
 * @property vin The index of the spent input, interpreted according to [type].
 * @property amount The asset amount consumed by this input.
 * @property txId For [Type.INTENT] inputs, the id of the transaction being referenced; `null`
 * for [Type.LOCAL] inputs.
 */
class AssetInput(
    val type: Type,
    val vin: Int,
    val amount: Long,
    val txId: ByteArray? = null,
) {
    private fun validate() {
        require(vin >= 0) { "Invalid vin: $vin" }
        if (type == Type.INTENT) {
            requireNotNull(txId) { "Missing input intent txid" }
            require(txId.size == TX_HASH_SIZE) { "Invalid intent txid length" }
            require(!txId.all { it == 0.toByte() }) { "Missing input intent txid" }
        }
    }

    fun serialize(): ByteArray {
        validate()
        val output = ByteArrayOutput()
        serializeTo(output)
        return output.toByteArray()
    }

    fun serializeTo(output: ByteArrayOutput) {
        output.write(type.ordinal)
        if (type == Type.INTENT && txId != null) {
            output.writeBytes(txId)
        }
        output.writeUInt16LE(vin)
        output.writeVarInt(amount)
    }

    /** The kind of input being referenced. */
    enum class Type {
        /** No reference; not a valid value for a parsed [AssetInput]. */
        UNSPECIFIED,

        /** References an input of the same transaction as the containing [AssetGroup]. */
        LOCAL,

        /** References an input of an external intent transaction, identified by [txId]. */
        INTENT,
        ;

        companion object {
            /**
             * Maps the single-byte wire encoding to a [Type].
             *
             * @param value The encoded type byte: `0` for [LOCAL], `1` for [INTENT], `2` for
             * [UNSPECIFIED].
             * @throws IllegalArgumentException if [value] is not one of the above.
             */
            fun fromByte(value: Byte): Type =
                when (value) {
                    0.toByte() -> LOCAL
                    1.toByte() -> INTENT
                    2.toByte() -> UNSPECIFIED
                    else -> throw IllegalArgumentException("Invalid asset input type: $value")
                }
        }
    }

    companion object {
        /**
         * Parses an [AssetInput] from [input]'s binary representation: a type byte followed by,
         * for [Type.LOCAL], a little-endian uint16 [vin] and a var-int [amount]; or for
         * [Type.INTENT], a 32-byte [txId] followed by [vin] and [amount] in the same encoding.
         *
         * @param input The buffer to read from.
         * @return The parsed [AssetInput].
         * @throws IllegalArgumentException if the type byte is invalid or is [Type.UNSPECIFIED].
         */
        fun fromBytesInput(input: ByteArrayInput): AssetInput =
            when (val type = Type.fromByte(input.read().toByte())) {
                Type.LOCAL -> {
                    val vin = input.readUInt16LE()
                    val amount = input.readVarIntToLong()
                    AssetInput(Type.LOCAL, vin, amount)
                }
                Type.INTENT -> {
                    val txId = input.readNBytes(TX_HASH_SIZE)
                    val vin = input.readUInt16LE()
                    val amount = input.readVarIntToLong()
                    AssetInput(Type.INTENT, vin, amount, txId)
                }
                Type.UNSPECIFIED -> throw IllegalArgumentException("Asset input type unspecified")
            }

        fun createIntent(
            intentTxId: ByteArray,
            index: Int,
            amount: Long,
        ): AssetInput {
            require(intentTxId.size == TX_HASH_SIZE) { "Invalid input intent txid length" }
            val input = AssetInput(Type.INTENT, index, amount, intentTxId)
            input.validate()
            return input
        }
    }
}
