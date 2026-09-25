package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.readNBytes

/**
 * Uniquely identifies an asset by the transaction that issued it and the index of the issuing
 * [AssetGroup] within that transaction's extension [Packet].
 *
 * The binary encoding is fixed-size: the 32-byte [txId] followed by [groupIndex] as an
 * unsigned little-endian 16-bit integer, for a total of [ASSET_ID_SIZE] bytes.
 *
 * @property txId The id of the transaction whose extension packet issued this asset.
 * @property groupIndex The index of the issuing [AssetGroup] within that packet's groups.
 */
class AssetId(
    val txId: ByteArray,
    val groupIndex: Int,
) {
    /** The lowercase hex encoding of [serialize]. */
    override fun toString(): String = serialize().toHexString().lowercase()

    fun validate() {
        require(txId.isNotEmpty()) { "Missing transaction id" }
        require(txId.size == TX_HASH_SIZE) { "Invalid txid length" }
        require(groupIndex in 0..0xFFFF) { "Group index cannot be negative" }
    }

    /** Serializes this asset id to its fixed-size binary representation. */
    fun serialize(): ByteArray {
        val output = ByteArrayOutput()
        serializeTo(output)
        return output.toByteArray()
    }

    /** Writes this asset id's binary representation to [output]. */
    fun serializeTo(output: ByteArrayOutput) {
        validate()
        output.write(txId)
        output.writeUInt16LE(groupIndex)
    }

    companion object {
        /**
         * Parses an [AssetId] from [input]'s fixed-size binary representation.
         *
         * @param input The buffer to read from; must have at least [ASSET_ID_SIZE] bytes
         * available.
         * @return The parsed [AssetId].
         * @throws IllegalArgumentException if fewer than [ASSET_ID_SIZE] bytes are available.
         */
        fun fromBytesInput(input: ByteArrayInput): AssetId {
            require(input.availableBytes >= ASSET_ID_SIZE) { "Invalid asset id length: got ${input.availableBytes} bytes" }
            val txId = input.readNBytes(TX_HASH_SIZE)
            val index = input.readUInt16LE()
            requireNotNull(txId) { "Missing txId" }
            return AssetId(txId, index)
        }
    }
}
