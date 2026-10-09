package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput

/**
 * The Arkade asset [ExtensionPacket]: a list of [AssetGroup]s describing the asset issuance and
 * transfers carried by a transaction.
 *
 * @property groups The asset groups carried by this packet; must be non-empty.
 */
class Packet(
    val groups: List<AssetGroup>,
) : ExtensionPacket {
    override val type: Byte = PACKET_TYPE

    /**
     * Validates this packet's fields.
     *
     * @throws IllegalArgumentException if [groups] is empty; if two groups share the same
     * non-null [AssetGroup.assetId]; or if a group's [AssetGroup.controlAsset] is a
     * [AssetRef.Type.BY_GROUP] reference whose [AssetRef.groupIndex] is out of range for
     * [groups].
     */
    override fun validate() {
        require(groups.isNotEmpty()) { "Missing assets" }

        val seenAssetIds: HashSet<String> = hashSetOf()

        groups.forEach { group ->
            val assetId = group.assetId
            if (assetId != null) {
                val isNotSeen = seenAssetIds.add(group.assetId.toString())
                require(isNotSeen) { "Duplicate asset group for asset: ${group.assetId}" }
            }

            val controlAsset = group.controlAsset

            if (controlAsset != null && controlAsset.type == AssetRef.Type.BY_GROUP) {
                val groupIndex = requireNotNull(controlAsset.groupIndex) { "Missing group control asset index" }
                if (groupIndex >= groups.size) {
                    throw IllegalArgumentException(
                        "Invalid control asset group index: ${controlAsset.groupIndex} out of range [0, ${groups.size - 1}]",
                    )
                }
            }
        }
    }

    /**
     * Not yet implemented.
     *
     * @throws NotImplementedError always; there is currently no way to serialize a [Packet] back
     * to its binary representation.
     */
    override fun serializePacketData(): ByteArray {
        validate()
        val output = ByteArrayOutput()
        output.writeVarInt(groups.size.toLong())
        groups.forEach { group ->
            group.serializeTo(output)
        }
        return output.toByteArray()
    }

    companion object {
        /** The [ExtensionPacket.type] byte identifying an asset [Packet]. */
        const val PACKET_TYPE: Byte = 0x00

        fun create(groups: List<AssetGroup>): Packet {
            val packet = Packet(groups)
            packet.validate()
            return packet
        }

        /**
         * Parses a [Packet] from its complete binary representation.
         *
         * @param bytes The packet's raw body bytes, as extracted from an [Extension] payload.
         * @return The parsed and [validate]d [Packet].
         * @throws IllegalArgumentException if [bytes] is empty, contains a malformed group, or
         * has trailing bytes left over after parsing the declared number of groups; or if the
         * parsed packet fails [validate].
         */
        fun fromBytes(bytes: ByteArray): Packet {
            val input = ByteArrayInput(bytes)
            return fromBytesInput(input)
        }

        /**
         * Parses a [Packet] from [input]: a var-int group count followed by that many
         * [AssetGroup]s, with no trailing bytes permitted afterward.
         *
         * @throws IllegalArgumentException if [input] is empty, the group count is truncated,
         * malformed, or exceeds [Int.MAX_VALUE], a group is malformed or missing,
         * or bytes remain in [input] after reading the declared number of groups; or if the
         * parsed packet fails [validate].
         */
        private fun fromBytesInput(input: ByteArrayInput): Packet {
            require(input.availableBytes > 0) { "Missing packet bytes" }

            val count = input.readVarIntToInt()

            val groups: MutableList<AssetGroup> = mutableListOf()
            (0 until count).forEach { _ ->
                require(input.availableBytes > 0) { "Missing group bytes" }
                groups.add(AssetGroup.fromBytesInput(input))
            }

            require(input.availableBytes == 0) { "Invalid packet length, left ${input.availableBytes} bytes" }

            val packet = Packet(groups)
            packet.validate()
            return packet
        }
    }
}
