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
        require(!txId.all { it == 0.toByte() }) { "Empty txId" }
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
        fun create(
            txId: ByteArray,
            groupIndex: Int,
        ): AssetId {
            require(txId.isNotEmpty()) { "Missing txId" }
            require(txId.size == TX_HASH_SIZE) { "Invalid txId length" }
            require(groupIndex in 0..0xFFFF) { "Group index should be within the acceptable range, 0 <= groupIndex <= 65535" }
            val assetId = AssetId(txId, groupIndex)
            assetId.validate()
            return assetId
        }

        fun create(
            txIdHex: String,
            groupIndex: Int,
        ): AssetId {
            require(txIdHex.isNotEmpty()) { "Missing txId" }
            val txId = txIdHex.hexToByteArray()
            require(txId.size == TX_HASH_SIZE) {
                "Invalid txId length: got ${txId.size} bytes, expected $TX_HASH_SIZE bytes"
            }
            return create(txId, groupIndex)
        }

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
            val assetId = create(txId, index)
            return assetId
        }

        fun fromBytes(bytes: ByteArray): AssetId {
            require(bytes.isNotEmpty()) { "Missing asset id" }
            require(bytes.size >= ASSET_ID_SIZE) { "Invalid asset id length: got ${bytes.size} bytes, expected $ASSET_ID_SIZE bytes" }
            return fromBytesInput(ByteArrayInput(bytes))
        }

        fun fromString(hex: String): AssetId {
            val bytes = hex.hexToByteArray()
            return fromBytes(bytes)
        }
    }
}
