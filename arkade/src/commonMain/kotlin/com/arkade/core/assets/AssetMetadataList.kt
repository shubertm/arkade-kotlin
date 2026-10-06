package com.arkade.core.assets

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput

class AssetMetadataList(
    val items: List<AssetMetadata>,
) {
    fun serialize(): ByteArray {
        val bytesOutput = ByteArrayOutput()
        serializeTo(bytesOutput)
        return bytesOutput.toByteArray()
    }

    fun serializeTo(output: ByteArrayOutput) {
        output.writeVarInt(items.size.toLong())
        items.forEach { item ->
            item.serializeTo(output)
        }
    }

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

    override fun toString(): String = serialize().toHexString()

    private fun computeLeafHash(metadata: AssetMetadata): ByteArray {
        val output = ByteArrayOutput()
        output.write(ARK_LEAF_VERSION)
        output.writeVarBytes(metadata.rawKey())
        output.writeVarBytes(metadata.rawValue())
        return taggedHash(ARK_LEAF_TAG, output.toByteArray())
    }

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

        fun fromBytesInput(input: ByteArrayInput): AssetMetadataList {
            val count = input.readVarIntToInt()
            val items = mutableListOf<AssetMetadata>()
            (0 until count).forEach { index ->
                items.add(AssetMetadata.fromBytesInput(input))
            }
            return AssetMetadataList(items)
        }

        fun fromBytes(bytes: ByteArray): AssetMetadataList {
            require(bytes.isNotEmpty()) { "Missing metadata list" }
            val bytesInput = ByteArrayInput(bytes)
            val metadataList = fromBytesInput(bytesInput)
            require(bytesInput.availableBytes == 0) { "Unexpected trailing bytes in metadata list" }
            return metadataList
        }

        fun fromString(hex: String): AssetMetadataList {
            val bytes =
                runCatching { hex.hexToByteArray() }.getOrElse { _ ->
                    throw IllegalArgumentException("Invalid metadata list format")
                }
            return fromBytes(bytes)
        }
    }
}
