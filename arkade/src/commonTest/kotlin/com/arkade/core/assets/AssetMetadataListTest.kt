package com.arkade.core.assets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetMetadataListTest {
    @Test
    fun serialize_asset_metadata_list_with_icon_url() {
        val metadataList =
            AssetMetadataList(
                listOf(
                    AssetMetadata.create("name", "testAsset"),
                    AssetMetadata.create("ticker", "TST"),
                    AssetMetadata.create("decimals", "0"),
                    AssetMetadata.create("icon", "https://example.com/icon.png"),
                ),
            )
        val serialized = metadataList.toString()
        assertEquals(
            "04046e616d6509746573744173736574067469636b65720354535408646563696d616c7301300469636f6e1c68747470733a2f2f6578616d706c652e636f6d2f69636f6e2e706e67",
            serialized,
        )
    }

    @Test
    fun serialize_asset_metadata_list_with_embedded_icon() {
        val metadataList =
            AssetMetadataList(
                listOf(
                    AssetMetadata.create("name", "testAsset"),
                    AssetMetadata.create("ticker", "TST"),
                    AssetMetadata.create("decimals", "0"),
                    AssetMetadata.create("icon", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUA"),
                ),
            )
        val serialized = metadataList.toString()
        assertEquals(
            "04046e616d6509746573744173736574067469636b65720354535408646563696d616c7301300469636f6e32646174613a696d6167652f706e673b6261736536342c6956424f5277304b47676f414141414e535568455567414141415541",
            serialized,
        )
    }

    @Test
    fun serialize_asset_metadata_list_with_random_data() {
        val metadataList =
            AssetMetadataList(
                listOf(
                    AssetMetadata.create("testKey", "testValue"),
                    AssetMetadata.create("{\"key\": \"key\"}", "{\"value\": \"value\"}"),
                    AssetMetadata.create("\u94a5\u5319", "\u4ef7\u503c"),
                ),
            )
        val serialized = metadataList.toString()
        assertEquals(
            "0307746573744b6579097465737456616c75650e7b226b6579223a20226b6579227d127b2276616c7565223a202276616c7565227d06e992a5e58c9906e4bbb7e580bc",
            serialized,
        )
    }

    @Test
    fun hash_asset_metadata_list_with_icon_url() {
        val metadataList =
            AssetMetadataList(
                listOf(
                    AssetMetadata.create("name", "testAsset"),
                    AssetMetadata.create("ticker", "TST"),
                    AssetMetadata.create("decimals", "0"),
                    AssetMetadata.create("icon", "https://example.com/icon.png"),
                ),
            )
        val hash = metadataList.hash().toHexString()
        assertEquals(
            "3a95a202409e237d575c8425685daa3a5880cd16e3c069b0df5c6228a234ce7b",
            hash,
        )
    }

    @Test
    fun hash_asset_metadata_list_with_embedded_icon() {
        val metadataList =
            AssetMetadataList(
                listOf(
                    AssetMetadata.create("name", "testAsset"),
                    AssetMetadata.create("ticker", "TST"),
                    AssetMetadata.create("decimals", "0"),
                    AssetMetadata.create("icon", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUA"),
                ),
            )
        val hash = metadataList.hash().toHexString()
        assertEquals(
            "99d136d0240c44d5915bfcc45c63e6030f9d02910956932bcfe7bca63fb582f4",
            hash,
        )
    }

    @Test
    fun hash_asset_metadata_list_with_random_data() {
        val metadataList =
            AssetMetadataList(
                listOf(
                    AssetMetadata.create("testKey", "testValue"),
                    AssetMetadata.create("{\"key\": \"key\"}", "{\"value\": \"value\"}"),
                    AssetMetadata.create("\u94a5\u5319", "\u4ef7\u503c"),
                ),
            )
        val hash = metadataList.hash().toHexString()
        assertEquals(
            "405828ca96e62f281e1e861e08f92813ac445d5ca2092877078dba25c8654596",
            hash,
        )
    }

    @Test
    fun should_fail_hashing_empty_metadata_list() {
        val metadataList = AssetMetadataList(emptyList())
        assertFailsWith<IllegalArgumentException> {
            metadataList.hash()
        }
    }

    @Test
    fun from_bytes_round_trip() {
        val metadataList =
            AssetMetadataList(
                listOf(
                    AssetMetadata.create("name", "testAsset"),
                    AssetMetadata.create("ticker", "TST"),
                ),
            )
        val bytes = metadataList.serialize()
        val deserialized = AssetMetadataList.fromBytes(bytes)
        assertEquals(2, deserialized.items.size)
        assertEquals("name", deserialized.items[0].key())
        assertEquals("ticker", deserialized.items[1].key())
        assertEquals("testAsset", deserialized.items[0].value())
        assertEquals("TST", deserialized.items[1].value())
    }
}
