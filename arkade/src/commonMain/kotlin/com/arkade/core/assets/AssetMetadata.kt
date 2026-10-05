package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput

/**
 * An opaque key/value pair attached to an [AssetGroup], used to carry auxiliary,
 * application-defined data such as asset name, ticker, or other issuance attributes.
 *
 * @property key The metadata key; must be non-empty.
 * @property value The metadata value; must be non-empty.
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

    fun rawKey() = key.copyOf()

    fun key() = key.decodeToString()

    fun rawValue() = value.copyOf()

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

    override fun toString(): String = serialize().toHexString()

    companion object {
        fun create(
            key: String,
            value: String,
        ): AssetMetadata = create(key.encodeToByteArray(), value.encodeToByteArray())

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
