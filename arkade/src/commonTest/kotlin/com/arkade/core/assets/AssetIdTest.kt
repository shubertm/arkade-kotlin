package com.arkade.core.assets

import com.ionspin.kotlin.bignum.integer.Quadruple
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetIdTest {
    @Test
    fun create_and_serialize_valid_asset_ids() {
        val testCases =
            listOf(
                Quadruple(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    0,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa0000",
                    "zero index",
                ),
                Quadruple(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    65535,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaffff",
                    "max index",
                ),
                Quadruple(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    2,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa0200",
                    "random txId and index",
                ),
            )

        testCases.forEach { case ->
            val assetId = AssetId.create(case.a, case.b)
            assertEquals(case.c, assetId.toString())
        }
    }

    @Test
    fun deserialize_valid_asset_ids_correctly() {
        val testCases =
            listOf(
                Quadruple(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa0000",
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    0,
                    "deserialize zero index",
                ),
                Quadruple(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa0200",
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    2,
                    "deserialize index 2",
                ),
            )
        testCases.forEach { case ->
            val assetId = AssetId.fromString(case.a)
            assertEquals(case.b, assetId.txId.toHexString())
            assertEquals(case.c, assetId.groupIndex)
        }
    }

    @Test
    fun should_fail_creating_asset_id_with_empty_tx_id() {
        assertFailsWith<IllegalArgumentException> {
            AssetId.create("", 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AssetId.create(byteArrayOf(), 0)
        }
    }

    @Test
    fun should_fail_creating_asset_id_with_invalid_byte_length() {
        assertFailsWith<IllegalArgumentException> {
            val bytes = ByteArray(20)
            AssetId.fromBytes(bytes)
        }
    }

    @Test
    fun should_fail_deserializing_asset_id_with_empty_tx_id() {
        assertFailsWith<IllegalArgumentException> {
            AssetId.fromString("00000000000000000000000000000000000000000000000000000000000000000100")
        }
    }

    @Test
    fun round_trip() {
        val assetId = AssetId.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 42)
        val assetIdHex = assetId.toString()
        val assetIdFromHex = AssetId.fromString(assetIdHex)
        assertEquals(assetIdHex, assetIdFromHex.toString())
    }

    @Test
    fun should_serialize_asset_id_34_bytes() {
        val assetId = AssetId.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0)
        assertEquals(34, assetId.serialize().size)
    }

    companion object {
        const val LOG_TAG = "AssetIdTest"
    }
}
