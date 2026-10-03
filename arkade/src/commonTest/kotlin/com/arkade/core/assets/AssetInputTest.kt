package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetInputTest {
    @Test
    fun serialize_local_asset_input() {
        val input = AssetInput.create(5, 10)
        assertEquals("0105000a", input.serialize().toHexString())
    }

    @Test
    fun serialize_intent_asset_input() {
        val input =
            AssetInput.createIntent(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                77,
                500,
            )
        assertEquals(
            "02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa4d00f403",
            input.serialize().toHexString(),
        )
    }

    @Test
    fun local_asset_input_round_trip() {
        val assetInput = AssetInput.create(5, 10)
        val parsedAssetInput = AssetInput.fromBytesInput(ByteArrayInput(assetInput.serialize()))
        assertEquals(AssetInput.Type.LOCAL, parsedAssetInput.type)
        assertEquals(5, parsedAssetInput.vIn)
        assertEquals(10, parsedAssetInput.amount)
    }

    @Test
    fun intent_asset_input_round_trip() {
        val assetInput =
            AssetInput.createIntent(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                77,
                500,
            )
        val parsedAssetInput = AssetInput.fromBytesInput(ByteArrayInput(assetInput.serialize()))
        assertEquals(AssetInput.Type.INTENT, parsedAssetInput.type)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", parsedAssetInput.txIdHex())
        assertEquals(77, parsedAssetInput.vIn)
        assertEquals(500, parsedAssetInput.amount)
    }

    @Test
    fun serialize_list_with_single_local_asset_input() {
        val inputs = listOf(AssetInput.create(5, 10))
        val inputsBytes = serializeInputs(inputs)
        assertEquals("010105000a", inputsBytes.toHexString())
    }

    @Test
    fun serialize_list_with_many_local_asset_input() {
        val inputs =
            listOf(
                AssetInput.create(1, 10),
                AssetInput.create(0, 2_100_000_000),
                AssetInput.create(25, 8_400_000_000_000),
            )
        val inputsBytes = serializeInputs(inputs)
        assertEquals("030101000a01000080eaade90701190080c090b8bcf401", inputsBytes.toHexString())
    }

    @Test
    fun serialize_list_with_single_intent_asset_input() {
        val inputs =
            listOf(
                AssetInput.createIntent(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    77,
                    500,
                ),
            )
        val inputsBytes = serializeInputs(inputs)
        assertEquals(
            "0102aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa4d00f403",
            inputsBytes.toHexString(),
        )
    }

    @Test
    fun serialize_list_with_many_intent_asset_input() {
        val inputs =
            listOf(
                AssetInput.createIntent(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    77,
                    500,
                ),
                AssetInput.createIntent(
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    1,
                    100_000_000_000,
                ),
            )
        val inputsBytes = serializeInputs(inputs)
        assertEquals(
            "0202aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa4d00f40302bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb010080d0dbc3f402",
            inputsBytes.toHexString(),
        )
    }

    @Test
    fun should_fail_creating_intent_asset_input_with_empty_tx_id() {
        assertFailsWith<IllegalArgumentException> {
            AssetInput.createIntent("", 10, 500)
        }
    }

    @Test
    fun should_fail_creating_intent_asset_input_with_zero_tx_id() {
        assertFailsWith<IllegalArgumentException> {
            AssetInput.createIntent("0000000000000000000000000000000000000000000000000000000000000000", 10, 500)
        }
    }

    @Test
    fun should_fail_creating_unspecified_asset_input_from_bytes() {
        assertFailsWith<IllegalArgumentException> {
            AssetInput.fromBytesInput(ByteArrayInput("0005000a".hexToByteArray()))
        }
    }

    @Test
    fun should_fail_creating_asset_input_with_unknown_type_from_bytes() {
        assertFailsWith<IllegalArgumentException> {
            AssetInput.fromBytesInput(ByteArrayInput("0305000a".hexToByteArray()))
        }
    }

    @Test
    fun should_fail_creating_asset_group_from_lists_with_mixed_asset_input_types() {
        val inputs =
            listOf(
                AssetInput.create(1, 10),
                AssetInput.createIntent(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    77,
                    500,
                ),
            )

        val throwable =
            assertFailsWith<IllegalArgumentException> {
                AssetGroup.create(
                    AssetId.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0),
                    null,
                    inputs,
                    listOf(
                        AssetOutput.create(
                            0,
                            10,
                        ),
                    ),
                    listOf(),
                )
            }

        assertEquals("Asset inputs must be of the same type", throwable.message)
    }

    @Test
    fun should_fail_creating_asset_group_from_lists_with_duplicate_asset_input_v_ins() {
        val inputs =
            listOf(
                AssetInput.create(1, 10),
                AssetInput.create(1, 200),
            )
        val throwable =
            assertFailsWith<IllegalArgumentException> {
                AssetGroup.create(
                    AssetId.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0),
                    null,
                    inputs,
                    listOf(
                        AssetOutput.create(
                            0,
                            10,
                        ),
                    ),
                    listOf(),
                )
            }
        assertEquals("Duplicate asset input vIn: 1", throwable.message)
    }

    private fun serializeInputs(inputs: List<AssetInput>): ByteArray {
        val inputsBytes = ByteArrayOutput()
        inputsBytes.writeVarInt(inputs.size.toLong())
        inputs.forEach { input ->
            input.serializeTo(inputsBytes)
        }
        return inputsBytes.toByteArray()
    }
}
