package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.readNBytes

/**
 * An input spending an existing asset amount into an [AssetGroup].
 *
 * [Type.LOCAL] inputs reference an input of the same transaction as the containing [AssetGroup] by
 * its [vIn] index. [Type.INTENT] inputs additionally carry the [txId] of an unrelated intent
 * transaction whose output is being consumed.
 *
 * @property type Whether this input references a local transaction input or an external intent.
 * @property vIn The index of the spent input, interpreted according to [type].
 * @property amount The asset amount consumed by this input.
 * @property txId For [Type.INTENT] inputs, the id of the transaction being referenced; `null`
 * for [Type.LOCAL] inputs.
 */
class AssetInput(
    val type: Type,
    val vIn: Int,
    val amount: Long,
    txId: ByteArray? = null,
) {
    private val txId: ByteArray? = txId?.copyOf()

    init {
        validate()
    }

    /** Returns a copy of the stored transaction id, or `null` if none was supplied. */
    fun txId() = txId?.copyOf()

    /** Returns the stored transaction id as lowercase hex, or `null` if none was supplied. */
    fun txIdHex() = txId()?.toHexString()

    /**
     * Returns this input's binary representation in a new byte array.
     *
     * @see serializeTo
     * @throws IllegalArgumentException if [amount] is negative.
     */
    fun serialize(): ByteArray {
        val output = ByteArrayOutput()
        serializeTo(output)
        return output.toByteArray()
    }

    /**
     * Appends the type byte, the 32-byte transaction id for an intent input, the little-endian
     * uint16 [vIn], and the var-int [amount] to [output].
     *
     * @throws IllegalArgumentException if [amount] is negative. The preceding fields remain
     * appended to [output] when this fails.
     */
    fun serializeTo(output: ByteArrayOutput) {
        validate()
        output.write(type.ordinal)
        if (type == Type.INTENT && txId != null) {
            output.writeBytes(txId)
        }
        output.writeUInt16LE(vIn)
        output.writeVarInt(amount)
    }

    /**
     * Returns this input's serialized bytes as lowercase hex.
     *
     * @throws IllegalArgumentException if [amount] is negative.
     */
    override fun toString(): String = serialize().toHexString()

    /**
     * Validates the input reference; [amount] is not checked.
     *
     * @throws IllegalArgumentException if [type] is [Type.UNSPECIFIED], [vIn] is outside
     * `0..0xFFFF`, or an intent input's [txId] is missing, is not 32 bytes, or is all zeros.
     */
    private fun validate() {
        require(type != Type.UNSPECIFIED) { "Asset input type not specified" }
        require(vIn in 0..0xFFFF) { "Invalid vIn: $vIn" }
        if (type == Type.INTENT) {
            requireNotNull(txId) { "Missing input intent txId" }
            require(txId.size == TX_HASH_SIZE) { "Invalid intent txId length" }
            require(!txId.all { it == 0.toByte() }) { "Missing input intent txId" }
        }
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
             * @param value The encoded type byte: `0` for [UNSPECIFIED], `1` for [LOCAL], `2` for
             * [INTENT].
             * @throws IllegalArgumentException if [value] is not one of the above.
             */
            fun fromByte(value: Int): Type =
                when (value) {
                    0 -> UNSPECIFIED
                    1 -> LOCAL
                    2 -> INTENT
                    else -> throw IllegalArgumentException("Invalid asset input type: $value")
                }
        }
    }

    companion object {
        /**
         * Parses an [AssetInput] from [input]'s binary representation: a type byte followed by,
         * for [Type.LOCAL], a little-endian uint16 [vIn] and a var-int [amount]; or for
         * [Type.INTENT], a 32-byte [txId] followed by [vIn] and [amount] in the same encoding.
         *
         * Validates the parsed input reference, rejecting missing or all-zero intent transaction
         * ids. An amount of zero is allowed.
         *
         * @param input The buffer to read from.
         * @return The parsed [AssetInput].
         * @throws IllegalArgumentException if the type byte is missing, invalid, or [Type.UNSPECIFIED],
         * an intent's [txId] is truncated or all zeros, [vIn] is truncated, or [amount] is
         * truncated, malformed, or exceeds [Long.MAX_VALUE].
         */
        fun fromBytesInput(input: ByteArrayInput): AssetInput =
            when (Type.fromByte(input.read())) {
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

        /**
         * Creates an input referencing the containing transaction's input at [index].
         *
         * @param index The input index, in `0..0xFFFF`.
         * @param amount The asset amount to consume; stored without validation, including zero.
         * Negative amounts are rejected when serialized.
         * @throws IllegalArgumentException if [index] is outside `0..0xFFFF`.
         */
        fun create(
            index: Int,
            amount: Long,
        ): AssetInput {
            val input = AssetInput(Type.LOCAL, index, amount)
            return input
        }

        /**
         * Creates an intent input after validating its transaction id and input index.
         *
         * @param intentTxId The referenced intent transaction's 32-byte id; must not be all zeros.
         * @param index The input index in that transaction, in `0..0xFFFF`.
         * @param amount The asset amount to consume; stored without validation, including zero.
         * Negative amounts are rejected when serialized.
         * @throws IllegalArgumentException if [intentTxId] or [index] violates these constraints.
         */
        fun createIntent(
            intentTxId: ByteArray,
            index: Int,
            amount: Long,
        ): AssetInput {
            require(intentTxId.size == TX_HASH_SIZE) { "Invalid input intent txId length" }
            val input = AssetInput(Type.INTENT, index, amount, intentTxId)
            return input
        }

        /**
         * Creates an intent input from a hexadecimal transaction id.
         *
         * @param intentTxIdHex The referenced intent transaction's 32-byte id as 64 hexadecimal
         * digits, with either letter case; must not be all zeros.
         * @param index The input index in that transaction, in `0..0xFFFF`.
         * @param amount The asset amount to consume; stored without validation, including zero.
         * Negative amounts are rejected when serialized.
         * @throws IllegalArgumentException if [intentTxIdHex] is empty, is not valid hex, does not
         * encode 32 bytes, or is all zeros; or if [index] is outside `0..0xFFFF`.
         */
        fun createIntent(
            intentTxIdHex: String,
            index: Int,
            amount: Long,
        ): AssetInput {
            require(intentTxIdHex.isNotEmpty()) { "Missing input intent txId" }
            val intentTxId = intentTxIdHex.hexToByteArray()
            require(intentTxId.size == TX_HASH_SIZE) { "Invalid input intent txId length" }
            return AssetInput(Type.INTENT, index, amount, intentTxId)
        }
    }
}
