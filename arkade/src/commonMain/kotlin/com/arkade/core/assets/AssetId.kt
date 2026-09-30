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
 * @param txId The id of the transaction whose extension packet issued this asset; copied on construction.
 * @property groupIndex The index of the issuing [AssetGroup] within that packet's groups.
 * @throws IllegalArgumentException if [txId] is not 32 bytes, contains only zeros, or
 * [groupIndex] is outside `0..65535`.
 */
class AssetId(
    txId: ByteArray,
    val groupIndex: Int,
) {
    private val txId = txId.copyOf()

    init {
        validate()
    }

    /** Returns a copy of the issuing transaction id; modifying it does not affect this asset id. */
    fun txId(): ByteArray = txId.copyOf()

    /** Returns the issuing transaction id as 64 lowercase hexadecimal digits, in stored byte order. */
    fun txIdHex(): String = txId().toHexString()

    /** The lowercase hex encoding of [serialize]. */
    override fun toString(): String = serialize().toHexString().lowercase()

    /**
     * Checks that the transaction id and group index form a valid asset id.
     *
     * @throws IllegalArgumentException if the transaction id is not 32 bytes, contains only
     * zeros, or [groupIndex] is outside `0..65535`.
     */
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
        /**
         * Creates an asset id from the issuing transaction id and its issuing group index.
         *
         * @param txId The 32 transaction id bytes in serialization order; copied into the asset id.
         * @param groupIndex The index of the issuing [AssetGroup] within the transaction's [Packet].
         * @throws IllegalArgumentException if [txId] is not 32 bytes, contains only zeros, or
         * [groupIndex] is outside `0..65535`.
         */
        fun create(
            txId: ByteArray,
            groupIndex: Int,
        ): AssetId {
            require(txId.isNotEmpty()) { "Missing txId" }
            require(txId.size == TX_HASH_SIZE) { "Invalid txId length" }
            require(groupIndex in 0..0xFFFF) { "Group index should be within the acceptable range, 0 <= groupIndex <= 65535" }
            val assetId = AssetId(txId, groupIndex)
            return assetId
        }

        /**
         * Creates an asset id from a hexadecimal issuing transaction id and its issuing group index.
         *
         * @param txIdHex Exactly 64 hexadecimal digits, in either case, with no prefix or separators.
         * The decoded bytes retain their order in the asset id.
         * @param groupIndex The index of the issuing [AssetGroup] within the transaction's [Packet].
         * @throws IllegalArgumentException if [txIdHex] has an invalid format or length, decodes
         * to only zero bytes, or [groupIndex] is outside `0..65535`.
         */
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
         * Consumes [ASSET_ID_SIZE] bytes, leaving any trailing bytes unread. An all-zero
         * transaction id is rejected after those bytes have been consumed; insufficient input
         * is rejected without consuming any bytes.
         *
         * @param input The buffer to read from; must have at least [ASSET_ID_SIZE] bytes
         * available.
         * @return The parsed [AssetId].
         * @throws IllegalArgumentException if fewer than [ASSET_ID_SIZE] bytes are available
         * or the transaction id contains only zeros.
         */
        fun fromBytesInput(input: ByteArrayInput): AssetId {
            require(input.availableBytes >= ASSET_ID_SIZE) { "Invalid asset id length: got ${input.availableBytes} bytes" }
            val txId = input.readNBytes(TX_HASH_SIZE)
            val index = input.readUInt16LE()
            requireNotNull(txId) { "Missing txId" }
            val assetId = create(txId, index)
            return assetId
        }

        /**
         * Parses exactly [ASSET_ID_SIZE] bytes: a 32-byte transaction id followed by an unsigned
         * little-endian 16-bit group index.
         *
         * @throws IllegalArgumentException if [bytes] is not [ASSET_ID_SIZE] bytes long or
         * the transaction id contains only zeros.
         */
        fun fromBytes(bytes: ByteArray): AssetId {
            require(bytes.isNotEmpty()) { "Missing asset id" }
            require(bytes.size == ASSET_ID_SIZE) { "Invalid asset id length: got ${bytes.size} bytes, expected $ASSET_ID_SIZE bytes" }
            return fromBytesInput(ByteArrayInput(bytes))
        }

        /**
         * Parses the hexadecimal representation produced by [toString].
         *
         * @param hex Exactly 68 hexadecimal digits, in either case, with no prefix or separators.
         * @throws IllegalArgumentException if [hex] has an invalid format or length or the
         * decoded transaction id contains only zeros.
         */
        fun fromString(hex: String): AssetId {
            val bytes = hex.hexToByteArray()
            return fromBytes(bytes)
        }
    }
}
