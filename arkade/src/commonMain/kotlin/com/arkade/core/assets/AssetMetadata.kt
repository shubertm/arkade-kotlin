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
    val key: ByteArray,
    val value: ByteArray,
) {
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
        validate()
        val output = ByteArrayOutput()
        serializeTo(output)
        return output.toByteArray()
    }

    fun serializeTo(output: ByteArrayOutput) {
        output.writeVarBytes(key)
        output.writeVarBytes(value)
    }

    companion object {
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
            metadata.validate()
            return metadata
        }
    }
}
