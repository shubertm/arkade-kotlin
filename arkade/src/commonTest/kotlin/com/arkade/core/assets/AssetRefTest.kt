package com.arkade.core.assets

import com.arkade.core.assets.AssetRef.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetRefTest {
    @Test
    fun serialize_asset_ref_from_id() {
        val assetId = AssetId.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 42)
        val assetRef = AssetRef.fromId(assetId)
        assertEquals(
            "01aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2a00",
            assetRef.toString(),
        )
    }

    @Test
    fun serialize_asset_refs_from_group_indexes() {
        val testCases =
            listOf(
                Triple(0, "020000", "zero index"),
                Triple(5, "020500", "random index"),
                Triple(65535, "02ffff", "max index"),
            )
        testCases.forEach { (index, expected, name) ->
            val assetRef = AssetRef.fromGroupIndex(index)
            assertEquals(
                expected,
                assetRef.toString(),
            )
        }
    }

    @Test
    fun should_fail_creating_asset_ref_from_empty_bytes() {
        assertFailsWith<IllegalArgumentException> {
            AssetRef.fromBytes(byteArrayOf())
        }
    }

    @Test
    fun should_fail_creating_unspecified_asset_ref_from_bytes() {
        val throwable =
            assertFailsWith<IllegalArgumentException> {
                AssetRef.fromBytes("000005".hexToByteArray())
            }
        assertEquals("Asset ref type unspecified", throwable.message)
    }

    @Test
    fun should_fail_creating_unknown_asset_ref_from_bytes() {
        val throwable =
            assertFailsWith<IllegalArgumentException> {
                AssetRef.fromBytes("030005".hexToByteArray())
            }
        assertEquals("Unknown asset ref type: 3", throwable.message)
    }

    @Test
    fun should_fail_creating_asset_ref_with_invalid_length_from_bytes() {
        assertFailsWith<IllegalArgumentException> {
            AssetRef.fromBytes("0200".hexToByteArray())
        }
    }

    @Test
    fun asset_ref_by_id_round_trip() {
        val assetId = AssetId.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 7)
        val assetRef = AssetRef.fromId(assetId)
        val deserializedAssetRef = AssetRef.fromBytes(assetRef.serialize())
        assertEquals(Type.BY_ID, deserializedAssetRef.type)
        assertEquals(7, deserializedAssetRef.assetId?.groupIndex)
    }

    @Test
    fun asset_ref_by_group_round_trip() {
        val assetRef = AssetRef.fromGroupIndex(42)
        val deserializedAssetRef = AssetRef.fromBytes(assetRef.serialize())
        assertEquals(Type.BY_GROUP, deserializedAssetRef.type)
        assertEquals(42, deserializedAssetRef.groupIndex)
    }
}
