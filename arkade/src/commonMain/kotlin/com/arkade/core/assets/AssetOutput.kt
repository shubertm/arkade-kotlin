package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput

/**
 * An output of the containing transaction that receives an asset amount from an [AssetGroup].
 *
 * @property vout The index of the transaction output receiving the asset amount.
 * @property amount The asset amount assigned to that output.
 */
class AssetOutput(
    val vout: Int,
    val amount: Long,
) {
    private val type = Type.LOCAL

    /**
     * Validates this output's fields.
     *
     * @throws IllegalArgumentException if [vout] is negative or [amount] is not strictly
     * positive.
     */
    fun validate() {
        require(vout >= 0) { "invalid vout: $vout" }
        require(amount > 0) { "asset output amount must be greater than 0" }
    }

    fun serialize(): ByteArray {
        val output = ByteArrayOutput()
        serializeTo(output)
        return output.toByteArray()
    }

    fun serializeTo(output: ByteArrayOutput) {
        validate()
        output.write(Type.LOCAL.ordinal)
        output.writeUInt16LE(vout)
        output.writeVarInt(amount)
    }

    /** The kind of output being described; currently only [LOCAL] is supported. */
    private enum class Type {
        /** No reference; not a valid value for a parsed [AssetOutput]. */
        UNSPECIFIED,

        /** References an output of the same transaction as the containing [AssetGroup]. */
        LOCAL,
        ;

        fun toByte(): Byte = this.ordinal.toByte()
    }

    companion object {
        /**
         * Parses an [AssetOutput] from [input]'s binary representation: a type byte, which must
         * encode [Type.LOCAL], followed by a little-endian uint16 [vout] and a var-int [amount].
         *
         * @param input The buffer to read from.
         * @return The parsed and [validate]d [AssetOutput].
         * @throws IllegalArgumentException if the type byte is [Type.UNSPECIFIED] or any other
         * value than [Type.LOCAL], or if the parsed fields fail [validate].
         */
        fun fromBytesInput(input: ByteArrayInput): AssetOutput {
            val type = input.read().toByte()
            require(type != Type.UNSPECIFIED.toByte()) { "output type unspecified" }
            require(type == Type.LOCAL.toByte()) { "invalid asset output type: $type" }

            val vout = input.readUInt16LE()
            val amount = input.readVarIntToLong()
            val output = AssetOutput(vout, amount)
            output.validate()
            return output
        }
    }
}
