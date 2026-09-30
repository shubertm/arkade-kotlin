package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetOutputTest {
    @Test
    fun create_and_serialize_asset_output() {
        val output = AssetOutput.create(5, 10)
        assertEquals("0105000a", output.serialize().toHexString())
    }

    @Test
    fun create_round_trip() {
        val assetOutput = AssetOutput.create(5, 10)
        val parsedAssetOutput = AssetOutput.fromBytesInput(ByteArrayInput(assetOutput.serialize()))
        assertEquals(5, parsedAssetOutput.vOut)
        assertEquals(10, parsedAssetOutput.amount)
    }

    @Test
    fun serialized_asset_output_should_include_type_byte() {
        val output = AssetOutput.create(5, 10)
        val outputBytes = output.serialize()
        assertEquals(0x01, outputBytes[0]) // type byte
        assertEquals(0x05, outputBytes[1]) // vOut low
        assertEquals(0x00, outputBytes[2]) // vOut high
        assertEquals(0x0a, outputBytes[3]) // amount
    }

    @Test
    fun serialize_list_with_single_asset_output() {
        val outputs = listOf(AssetOutput.create(5, 10))
        val outputsBytes = serializeOutputs(outputs)
        assertEquals("010105000a", outputsBytes.toHexString())
    }

    @Test
    fun serialize_list_with_many_asset_outputs() {
        val outputs =
            listOf(
                AssetOutput.create(1, 10),
                AssetOutput.create(0, 2_100_000_000),
                AssetOutput.create(25, 8_400_000_000_000),
            )
        val outputsBytes = serializeOutputs(outputs)
        assertEquals("030101000a01000080eaade90701190080c090b8bcf401", outputsBytes.toHexString())
    }

    @Test
    fun should_fail_creating_asset_output_with_zero_amount() {
        assertFailsWith<IllegalArgumentException> {
            AssetOutput.create(5, 0)
        }
    }

    @Test
    fun should_fail_creating_unspecified_asset_output_from_bytes() {
        val throwable =
            assertFailsWith<IllegalArgumentException> {
                AssetOutput.fromBytesInput(ByteArrayInput("00050001".hexToByteArray()))
            }
        assertEquals("Asset output type unspecified", throwable.message)
    }

    @Test
    fun should_fail_creating_asset_output_with_unknown_type_from_bytes() {
        val throwable =
            assertFailsWith<IllegalArgumentException> {
                AssetOutput.fromBytesInput(ByteArrayInput("03050001".hexToByteArray()))
            }
        assertEquals("Invalid asset output type: 3", throwable.message)
    }

    @Test
    fun should_fail_creating_asset_output_with_zero_amount_from_bytes() {
        val throwable =
            assertFailsWith<IllegalArgumentException> {
                AssetOutput.fromBytesInput(ByteArrayInput("01050000".hexToByteArray()))
            }
        assertEquals("Asset output amount must be greater than 0", throwable.message)
    }

    @Test
    fun should_fail_creating_asset_group_from_list_with_duplicate_asset_output_v_outs() {
        val outputs =
            listOf(
                AssetOutput.create(5, 10),
                AssetOutput.create(5, 70),
            )
        val throwable =
            assertFailsWith<IllegalArgumentException> {
                AssetGroup.create(
                    AssetId.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0),
                    null,
                    listOf(
                        AssetInput.create(0, 50),
                    ),
                    outputs,
                    listOf(),
                )
            }
        assertEquals("Duplicate asset output vOut: 5", throwable.message)
    }

    private fun serializeOutputs(outputs: List<AssetOutput>): ByteArray {
        val outputsBytes = ByteArrayOutput()
        outputsBytes.writeVarInt(outputs.size.toLong())
        outputs.forEach { output ->
            output.serializeTo(outputsBytes)
        }
        return outputsBytes.toByteArray()
    }
}
