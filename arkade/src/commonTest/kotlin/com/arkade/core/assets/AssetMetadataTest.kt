package com.arkade.core.assets

import com.ionspin.kotlin.bignum.integer.Quadruple
import fr.acinq.bitcoin.io.ByteArrayInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetMetadataTest {
    @Test
    fun create_and_serialize_valid_metadata() {
        val metadataList =
            listOf(
                Quadruple("testKey", "testValue", "07746573744b6579097465737456616c7565", "simple"),
                Quadruple(
                    "{\"key\": \"key\"}",
                    "{\"value\": \"value\"}",
                    "0e7b226b6579223a20226b6579227d127b2276616c7565223a202276616c7565227d",
                    "nested json",
                ),
                Quadruple("0", "1", "01300131", "short key value"),
            )
        metadataList.forEach { testMetadata ->
            val metadata = AssetMetadata.create(testMetadata.a, testMetadata.b)
            val serializedMetadata = metadata.serialize().toHexString()
            assertEquals(testMetadata.c, serializedMetadata)
        }
    }

    @Test
    fun create_and_serialize_chinese_metadata_chars() {
        // Fixture: "another alphabet" — key=钥匙, value=价值
        val metadata = AssetMetadata.create("\u94a5\u5319", "\u4ef7\u503c")
        val serializedMetadata = metadata.toString()
        assertEquals("06e992a5e58c9906e4bbb7e580bc", serializedMetadata)
    }

    @Test
    fun create_and_serialize_emoji_metadata() {
        // Fixture: "emoji" — key=🔑, value=👾
        val metadata = AssetMetadata.create("\uD83D\uDD11", "\uD83D\uDC7E")
        val serializedMetadata = metadata.toString()
        assertEquals("04f09f949104f09f91be", serializedMetadata)
    }

    @Test
    fun should_fail_creating_metadata_with_empty_key() {
        assertFailsWith<IllegalArgumentException> {
            AssetMetadata.create("", "value")
        }
    }

    @Test
    fun should_fail_creating_metadata_with_empty_value() {
        assertFailsWith<IllegalArgumentException> {
            AssetMetadata.create("key", "")
        }
    }

    @Test
    fun round_trip() {
        val metadata = AssetMetadata.create("testKey", "testValue")
        val serializedMetadata = metadata.serialize()
        val deserializedMetadata =
            AssetMetadata.fromBytesInput(
                ByteArrayInput(serializedMetadata),
            )
        assertEquals(metadata.key(), deserializedMetadata.key())
        assertEquals(metadata.value(), deserializedMetadata.value())
    }
}
