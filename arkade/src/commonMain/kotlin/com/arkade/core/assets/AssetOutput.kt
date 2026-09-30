package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput

/**
 * An output of the containing transaction that receives an asset amount from an [AssetGroup].
 *
 * @property vOut The index of the transaction output receiving the asset amount.
 * @property amount The asset amount assigned to that output.
 */
class AssetOutput(
    val vOut: Int,
    val amount: Long,
) {
    init {
        validate()
    }

    fun serialize(): ByteArray {
        val output = ByteArrayOutput()
        serializeTo(output)
        return output.toByteArray()
    }

    /**
     * Appends this output's type byte, little-endian uint16 [vOut], and var-int [amount] to [output].
     *
     * @throws IllegalArgumentException if [vOut] is outside `0..0xFFFF` or [amount] is not
     * strictly positive. A [vOut] above `0xFFFF` leaves the type byte appended before failing.
     */
    fun serializeTo(output: ByteArrayOutput) {
        validate()
        output.write(Type.LOCAL.ordinal)
        output.writeUInt16LE(vOut)
        output.writeVarInt(amount)
    }

    /** Returns this output's serialized bytes as lowercase hex. */
    override fun toString(): String = serialize().toHexString()

    /**
     * Validates this output's fields.
     *
     * @throws IllegalArgumentException if [vOut] is outside `0..0xFFFF` or [amount] is not strictly
     * positive.
     */
    private fun validate() {
        require(vOut in 0..0xFFFF) { "Invalid vOut: $vOut" }
        require(amount > 0) { "Asset output amount must be greater than 0" }
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
         * Creates an output assigning an asset amount to the containing transaction's output at [vOut].
         *
         * @param vOut The output index, in `0..0xFFFF`.
         * @param amount The asset amount to assign; must be strictly positive.
         * @throws IllegalArgumentException if [vOut] or [amount] violates these constraints.
         */
        fun create(
            vOut: Int,
            amount: Long,
        ): AssetOutput = AssetOutput(vOut, amount)

        /**
         * Parses an [AssetOutput] from [input]'s binary representation: a type byte, which must
         * encode [Type.LOCAL], followed by a little-endian uint16 [vOut] and a var-int [amount].
         *
         * @param input The buffer to read from.
         * @return The parsed and [validate]d [AssetOutput].
         * @throws IllegalArgumentException if the type byte is [Type.UNSPECIFIED] or any other
         * value than [Type.LOCAL], if [vOut] or [amount] is truncated, if [amount] is malformed
         * or exceeds [Long.MAX_VALUE], or if the parsed fields fail [validate].
         */
        fun fromBytesInput(input: ByteArrayInput): AssetOutput {
            val type = input.read().toByte()
            require(type != Type.UNSPECIFIED.toByte()) { "Asset output type unspecified" }
            require(type == Type.LOCAL.toByte()) { "Invalid asset output type: $type" }

            val vOut = input.readUInt16LE()
            val amount = input.readVarIntToLong()
            val output = AssetOutput(vOut, amount)
            return output
        }
    }
}
