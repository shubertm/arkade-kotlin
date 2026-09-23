package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput

/**
 * References an asset either by its globally unique [AssetId] or by the index of the
 * [AssetGroup] that issues/controls it within the same extension [Packet].
 *
 * Exactly one of [assetId] or [groupIndex] is populated, depending on [type]: [Type.BY_ID]
 * populates [assetId], and [Type.BY_GROUP] populates [groupIndex].
 *
 * @property type Which of [assetId] or [groupIndex] identifies the referenced asset.
 * @property assetId The referenced asset's id, present only when [type] is [Type.BY_ID].
 * @property groupIndex The index of the referenced [AssetGroup] within the same packet, present
 * only when [type] is [Type.BY_GROUP].
 */
class AssetRef(
    val type: Type,
    val assetId: AssetId?,
    val groupIndex: Int?,
) {
    fun serialize(): ByteArray {
        val output = ByteArrayOutput()
        serializeTo(output)
        return output.toByteArray()
    }

    fun serializeTo(output: ByteArrayOutput) {
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

    /** The encoding used to identify the referenced asset. */
    enum class Type {
        /** No reference; not a valid value for a parsed [AssetRef]. */
        UNSPECIFIED,

        /** The asset is identified by its [AssetId]. */
        BY_ID,

        /** The asset is identified by the index of its issuing [AssetGroup]. */
        BY_GROUP,
        ;

        companion object {
            /**
             * Maps the single-byte wire encoding to a [Type].
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
         * @param input The buffer to read from.
         * @return The parsed [AssetRef].
         * @throws IllegalArgumentException if the type byte is invalid or is [Type.UNSPECIFIED].
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
    }
}
