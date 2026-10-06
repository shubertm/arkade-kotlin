package com.arkade.core.assets

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput

class AssetMetadataList(
    val items: List<AssetMetadata>,
) {
    /**
     * Returns the var-int item count followed by each entry's length-prefixed key and value,
     * preserving list order. An empty list is encoded as a single zero byte.
     */
    fun serialize(): ByteArray {
        val bytesOutput = ByteArrayOutput()
        serializeTo(bytesOutput)
        return bytesOutput.toByteArray()
    }

    /** Appends the encoding described by [serialize] to [output]. */
    fun serializeTo(output: ByteArrayOutput) {
        output.writeVarInt(items.size.toLong())
        items.forEach { item ->
            item.serializeTo(output)
        }
    }

    /**
     * Returns the 32-byte Arkade metadata Merkle root. Leaves follow list order; each pair
     * is sorted by unsigned hash bytes before hashing, and an unpaired node is carried to
     * the next level unchanged. A single entry returns its leaf hash.
     *
     * @throws IllegalArgumentException if [items] is empty.
     */
    fun hash(): ByteArray {
        require(items.isNotEmpty()) { "Missing metadata list" }
        var current = items.map { item -> computeLeafHash(item) }

        while (current.size > 1) {
            val next = mutableListOf<ByteArray>()
            ((0 until current.size) step 2).forEach { index ->
                if (index + 1 < current.size) {
                    next.add(computeBranchHash(current[index], current[index + 1]))
                } else {
                    next.add(current[index])
                }
            }
            current = next
        }
        return current[0]
    }

    /** Returns the serialized list as lowercase hexadecimal, including the item count. */
    override fun toString(): String = serialize().toHexString()

    /** Hashes version `0x00` and the length-prefixed key and value with the Arkade leaf tag. */
    private fun computeLeafHash(metadata: AssetMetadata): ByteArray {
        val output = ByteArrayOutput()
        output.write(ARK_LEAF_VERSION)
        output.writeVarBytes(metadata.rawKey())
        output.writeVarBytes(metadata.rawValue())
        return taggedHash(ARK_LEAF_TAG, output.toByteArray())
    }

    /** Hashes two child hashes in unsigned lexicographic order with the Arkade branch tag. */
    private fun computeBranchHash(
        a: ByteArray,
        b: ByteArray,
    ): ByteArray {
        val (smaller, larger) =
            if (compareBytes(a, b) < 0) {
                a to b
            } else {
                b to a
            }
        return taggedHash(ARK_BRANCH_TAG, smaller, larger)
    }

    /**
     * Compares unsigned bytes lexicographically, ordering a matching prefix before a longer
     * array. Returns a negative value, zero, or a positive value for less, equal, or greater.
     */
    private fun compareBytes(
        a: ByteArray,
        b: ByteArray,
    ): Int {
        val minLength = minOf(a.size, b.size)
        for (i in 0 until minLength) {
            val aByte = a[i].toUByte()
            val bByte = b[i].toUByte()
            if (aByte < bByte) return -1
            if (aByte > bByte) return 1
        }
        return a.size.compareTo(b.size)
    }

    /** Returns SHA-256 of the UTF-8 tag's SHA-256 repeated twice, then [bytesData] in order. */
    private fun taggedHash(
        tag: String,
        vararg bytesData: ByteArray,
    ): ByteArray {
        val tagHash = ByteVector(tag.encodeToByteArray()).sha256()

        var byteVector = tagHash.concat(tagHash)
        for (bytes in bytesData) {
            byteVector = byteVector.concat(bytes)
        }
        return byteVector.sha256().toByteArray()
    }

    companion object {
        private const val ARK_LEAF_VERSION = 0x00
        private const val ARK_LEAF_TAG = "ArkadeAssetLeaf"
        private const val ARK_BRANCH_TAG = "ArkadeAssetBranch"

        /**
         * Reads a var-int count and that many metadata entries from [input], advancing its
         * position and leaving subsequent bytes unread. A zero count returns an empty list.
         *
         * @throws IllegalArgumentException if the count is truncated, malformed, or exceeds
         * [Int.MAX_VALUE], or an entry has a malformed length, truncated data, or an empty key or value.
         */
        fun fromBytesInput(input: ByteArrayInput): AssetMetadataList {
            val count = input.readVarIntToInt()
            val items = mutableListOf<AssetMetadata>()
            (0 until count).forEach { index ->
                items.add(AssetMetadata.fromBytesInput(input))
            }
            return AssetMetadataList(items)
        }

        /**
         * Parses exactly one serialized list, requiring all bytes to be consumed.
         * A zero item count is valid; an empty byte array is not.
         *
         * @throws IllegalArgumentException if [bytes] is empty, contains trailing bytes, or
         * fails the count or entry validation described by [fromBytesInput].
         */
        fun fromBytes(bytes: ByteArray): AssetMetadataList {
            require(bytes.isNotEmpty()) { "Missing metadata list" }
            val bytesInput = ByteArrayInput(bytes)
            val metadataList = fromBytesInput(bytesInput)
            require(bytesInput.availableBytes == 0) { "Unexpected trailing bytes in metadata list" }
            return metadataList
        }

        /**
         * Parses a hexadecimal list encoding using [fromBytes].
         *
         * @throws IllegalArgumentException if [hex] cannot be decoded as hexadecimal, or the
         * decoded bytes fail validation in [fromBytes].
         */
        fun fromString(hex: String): AssetMetadataList {
            val bytes =
                runCatching { hex.hexToByteArray() }.getOrElse { _ ->
                    throw IllegalArgumentException("Invalid metadata list format")
                }
            return fromBytes(bytes)
        }
    }
}
