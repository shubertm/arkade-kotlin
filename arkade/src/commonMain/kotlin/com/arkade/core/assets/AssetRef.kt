package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput

/**
 * References an asset either by its globally unique [AssetId] or by the index of the
 * [AssetGroup] that issues/controls it within the same extension [Packet].
 *
 * [type] selects the required field: [Type.BY_ID] uses [assetId], and [Type.BY_GROUP]
 * uses [groupIndex]. The other field is ignored by validation and serialization. Prefer
 * [fromId] and [fromGroupIndex], which leave the unused field null.
 *
 * The binary encoding starts with a type byte: `1` followed by a 34-byte [AssetId]
 * (35 bytes total), or `2` followed by an unsigned little-endian 16-bit group index
 * (3 bytes total). Type `0` ([Type.UNSPECIFIED]) is not a valid reference.
 *
 * @property type Which of [assetId] or [groupIndex] identifies the referenced asset.
 * @property assetId The referenced asset's id; required when [type] is [Type.BY_ID].
 * @property groupIndex The zero-based index of the referenced [AssetGroup] within the same
 * packet; required when [type] is [Type.BY_GROUP] and must be in `0..65535`.
 * @throws IllegalArgumentException if the selected field is null or a selected [groupIndex]
 * is outside `0..65535`.
 * @throws IllegalStateException if [type] is [Type.UNSPECIFIED].
 */
class AssetRef(
    val type: Type,
    val assetId: AssetId?,
    val groupIndex: Int?,
) {
    init {
        validate()
    }

    /**
     * Checks that the field selected by [type] is present and, for [Type.BY_GROUP], in range.
     *
     * The unused field is ignored. This does not check whether [groupIndex] identifies an
     * existing group in a particular [Packet].
     *
     * @throws IllegalArgumentException if the selected field is null or a selected [groupIndex]
     * is outside `0..65535`.
     * @throws IllegalStateException if [type] is [Type.UNSPECIFIED].
     */
    fun validate() {
        when (type) {
            Type.BY_ID -> {
                require(assetId != null) { "Missing asset id for ${type.name} asset ref" }
            }
            Type.BY_GROUP -> {
                require(groupIndex != null) { "Missing group index for ${type.name} asset ref" }
                require(groupIndex in 0..0xFFFF) { "Invalid group index: $groupIndex" }
            }
            Type.UNSPECIFIED -> throw IllegalStateException("Cannot validate unspecified asset ref")
        }
    }

    /** Returns the binary encoding: a type byte followed by the selected id or group index. */
    fun serialize(): ByteArray {
        val output = ByteArrayOutput()
        serializeTo(output)
        return output.toByteArray()
    }

    /** Validates this reference and appends its binary encoding to [output]. */
    fun serializeTo(output: ByteArrayOutput) {
        validate()
        output.write(type.ordinal)
        when (type) {
            Type.BY_ID -> {
                assetId!!.serializeTo(output)
            }
            Type.BY_GROUP -> {
                output.writeUInt16LE(groupIndex!!)
            }
            Type.UNSPECIFIED -> throw IllegalStateException("Cannot serialize unspecified asset ref")
        }
    }

    /** Returns the lowercase hexadecimal representation of [serialize], without a prefix. */
    override fun toString(): String = serialize().toHexString()

    /** The encoding used to identify the referenced asset; ordinal values are the wire type bytes. */
    enum class Type {
        /** Wire value `0`; rejected when constructing or parsing an [AssetRef]. */
        UNSPECIFIED,

        /** Wire value `1`; the asset is identified by its [AssetId]. */
        BY_ID,

        /** Wire value `2`; the asset is identified by a group index within the same [Packet]. */
        BY_GROUP,
        ;

        companion object {
            /**
             * Maps the single-byte wire encoding to a [Type].
             * Recognizes [UNSPECIFIED], although it cannot be used to construct an [AssetRef].
             *
             * @param value The encoded type byte: `0` for [UNSPECIFIED], `1` for [BY_ID], `2`
             * for [BY_GROUP].
             * @throws IllegalArgumentException if [value] is not one of the above.
             */
            fun fromByte(value: Byte): Type =
                when (value) {
                    0.toByte() -> UNSPECIFIED
                    1.toByte() -> BY_ID
                    2.toByte() -> BY_GROUP
                    else -> throw IllegalArgumentException("Unknown asset ref type: $value")
                }
        }
    }

    companion object {
        /**
         * Parses an [AssetRef] from [input]'s binary representation: a type byte followed by
         * either an [AssetId] (for [Type.BY_ID]) or a little-endian uint16 group index (for
         * [Type.BY_GROUP]).
         *
         * Consumes one reference (35 bytes for an id or 3 bytes for a group index), leaving
         * trailing bytes unread. Parsing failures may leave [input] partially consumed.
         *
         * @param input The buffer to read from.
         * @return The parsed [AssetRef].
         * @throws IllegalArgumentException if the input is empty or truncated, the type byte
         * is unknown or [Type.UNSPECIFIED], or the encoded asset id has an all-zero transaction id.
         */
        fun fromBytesInput(input: ByteArrayInput): AssetRef {
            val type = Type.fromByte(input.read().toByte())
            return when (type) {
                Type.BY_ID -> {
                    val assetId = AssetId.fromBytesInput(input)
                    AssetRef(Type.BY_ID, assetId = assetId, groupIndex = null)
                }
                Type.BY_GROUP -> {
                    val groupIndex = input.readUInt16LE()
                    AssetRef(Type.BY_GROUP, assetId = null, groupIndex = groupIndex)
                }
                Type.UNSPECIFIED -> throw IllegalArgumentException("Asset ref type unspecified")
            }
        }

        /**
         * Parses exactly one reference from [bytes], rejecting any trailing bytes.
         *
         * @throws IllegalArgumentException if [bytes] is empty, truncated, contains trailing
         * bytes, has an unknown or [Type.UNSPECIFIED] type byte, or encodes an asset id with
         * an all-zero transaction id.
         */
        fun fromBytes(bytes: ByteArray): AssetRef {
            require(bytes.isNotEmpty()) { "Missing asset ref" }
            val bytesInput = ByteArrayInput(bytes)
            val assetRef = fromBytesInput(bytesInput)
            require(bytesInput.availableBytes == 0) { "Unexpected trailing bytes in asset ref" }
            return assetRef
        }

        /** Creates a [Type.BY_ID] reference to [assetId], with a null [groupIndex]. */
        fun fromId(assetId: AssetId): AssetRef = AssetRef(Type.BY_ID, assetId, null)

        /**
         * Creates a [Type.BY_GROUP] reference with a null [assetId].
         *
         * @param groupIndex The zero-based index of the referenced [AssetGroup] in the same [Packet].
         * @throws IllegalArgumentException if [groupIndex] is outside `0..65535`.
         */
        fun fromGroupIndex(groupIndex: Int): AssetRef = AssetRef(Type.BY_GROUP, null, groupIndex)
    }
}
