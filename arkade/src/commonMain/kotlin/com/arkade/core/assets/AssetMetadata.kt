package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput

/**
 * An opaque key/value pair attached to an [AssetGroup], used to carry auxiliary,
 * application-defined data such as asset name, ticker, or other issuance attributes.
 *
 * @constructor Creates an entry with copies of [key] and [value].
 * @param key The metadata key; must be non-empty.
 * @param value The metadata value; must be non-empty.
 * @throws IllegalArgumentException if [key] or [value] is empty.
 */
class AssetMetadata(
    key: ByteArray,
    value: ByteArray,
) {
    private val key: ByteArray = key.copyOf()
    private val value: ByteArray = value.copyOf()

    init {
        validate()
    }

    /** Returns a copy of the key bytes; modifying it does not change this entry. */
    fun rawKey() = key.copyOf()

    /** Returns the key decoded as UTF-8, replacing malformed byte sequences with U+FFFD. */
    fun key() = key.decodeToString()

    /** Returns a copy of the value bytes; modifying it does not change this entry. */
    fun rawValue() = value.copyOf()

    /** Returns the value decoded as UTF-8, replacing malformed byte sequences with U+FFFD. */
    fun value() = value.decodeToString()

    /**
     * Validates this metadata entry's fields.
     *
     * @throws IllegalArgumentException if [key] or [value] is empty.
     */
    fun validate() {
        require(key.isNotEmpty()) { "Missing metadata key" }
        require(value.isNotEmpty()) { "Missing metadata value" }
    }

    fun serialize(): ByteArray {
        val output = ByteArrayOutput()
        serializeTo(output)
        return output.toByteArray()
    }

    fun serializeTo(output: ByteArrayOutput) {
        validate()
        output.writeVarBytes(key)
        output.writeVarBytes(value)
    }

    /** Returns the serialized entry as hexadecimal, including both fields' length prefixes. */
    override fun toString(): String = serialize().toHexString()

    companion object {
        /**
         * Creates an entry by encoding [key] and [value] as UTF-8, replacing malformed
         * character sequences with the encoding's replacement bytes.
         *
         * @throws IllegalArgumentException if [key] or [value] is empty.
         */
        fun create(
            key: String,
            value: String,
        ): AssetMetadata = create(key.encodeToByteArray(), value.encodeToByteArray())

        /**
         * Creates an entry with copies of [key] and [value]; the bytes need not be valid UTF-8.
         *
         * @throws IllegalArgumentException if [key] or [value] is empty.
         */
        fun create(
            key: ByteArray,
            value: ByteArray,
        ): AssetMetadata = AssetMetadata(key, value)

        /**
         * Parses an [AssetMetadata] from [input]'s binary representation: a var-length [key]
         * followed by a var-length [value], each encoded as in [readVarBytes].
         *
         * @param input The buffer to read from.
         * @return The parsed and [validate]d [AssetMetadata].
         * @throws IllegalArgumentException if either length-prefixed field is malformed (e.g.
         * declares a size larger than the remaining input), or if the parsed fields fail
         * [validate].
         */
        fun fromBytesInput(input: ByteArrayInput): AssetMetadata {
            val key =
                runCatching {
                    requireNotNull(input.readVarBytes())
                }.getOrElse { throw IllegalArgumentException("Invalid asset metadata length") }
            val value =
                runCatching {
                    requireNotNull(input.readVarBytes())
                }.getOrElse { throw IllegalArgumentException("Invalid asset metadata length") }

            val metadata = AssetMetadata(key, value)
            return metadata
        }
    }
}
