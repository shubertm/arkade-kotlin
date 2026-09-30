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

    fun txId() = txId?.copyOf()!!

    fun txIdHex() = txId().toHexString()

    fun serialize(): ByteArray {
        val output = ByteArrayOutput()
        serializeTo(output)
        return output.toByteArray()
    }

    fun serializeTo(output: ByteArrayOutput) {
        validate()
        output.write(type.ordinal)
        if (type == Type.INTENT && txId != null) {
            output.writeBytes(txId)
        }
        output.writeUInt16LE(vIn)
        output.writeVarInt(amount)
    }

    override fun toString(): String = serialize().toHexString()

    /**
     * Validates the input reference; [amount] is not checked.
     *
     * @throws IllegalArgumentException if [type] is [Type.UNSPECIFIED], [vin] is outside
     * `0..0xFFFF`, or an intent input's [txId] is missing, is not 32 bytes, or is all zeros.
     */
    private fun validate() {
        require(type != Type.UNSPECIFIED) { "Asset input type not specified" }
        require(vIn in 0..0xFFFF) { "Invalid vIn: $vIn" }
        if (type == Type.INTENT) {
            requireNotNull(txId) { "Missing input intent txid" }
            require(txId.size == TX_HASH_SIZE) { "Invalid intent txid length" }
            require(!txId.all { it == 0.toByte() }) { "Missing input intent txid" }
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
            fun fromByte(value: Byte): Type =
                when (value) {
                    0.toByte() -> UNSPECIFIED
                    1.toByte() -> LOCAL
                    2.toByte() -> INTENT
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
         * Does not validate the parsed input reference. If fewer than 32 bytes remain when
         * reading an intent's [txId], it is left `null` and [vin] and [amount] are read from
         * the remaining bytes.
         *
         * @param input The buffer to read from.
         * @return The parsed [AssetInput].
         * @throws IllegalArgumentException if the type byte is invalid or is [Type.UNSPECIFIED],
         * [vin] is truncated, or [amount] is truncated, malformed, or exceeds [Long.MAX_VALUE].
         */
        fun fromBytesInput(input: ByteArrayInput): AssetInput =
            when (Type.fromByte(input.read().toByte())) {
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
