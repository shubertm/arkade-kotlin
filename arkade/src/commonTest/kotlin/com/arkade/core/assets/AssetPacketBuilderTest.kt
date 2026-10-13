package com.arkade.core.assets

import com.arkade.core.isUnSpendable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssetPacketBuilderTest {
    @Test
    fun should_return_null_when_no_asset_inputs() {
        assertNull(
            AssetPacketBuilder.build(listOf(), listOf(), 0),
        )
    }

    @Test
    fun should_build_all_to_change_when_no_explicit_outputs() {
        val inputs =
            listOf(
                Triple(FAKE_ASSET_ID, 1, 1000L),
            )
        val txOut = AssetPacketBuilder.build(inputs, listOf(), 0)

        assertNotNull(txOut)
        assertTrue(txOut.isUnSpendable())

        val packet = Packet.fromScript(txOut.publicKeyScript.toByteArray())
        assertEquals(1, packet.groups.size)
        assertEquals(1, packet.groups[0].outputs.size)
        assertEquals(0, packet.groups[0].outputs[0].vOut)
        assertEquals(1000, packet.groups[0].outputs[0].amount)
    }

    @Test
    fun should_split_to_explicit_and_change_outputs() {
        val inputs =
            listOf(
                Triple(FAKE_ASSET_ID, 0, 1000L),
            )
        val outputs =
            listOf(
                Triple(FAKE_ASSET_ID, 1, 400L),
            )
        val txOut = AssetPacketBuilder.build(inputs, outputs, 0)

        assertNotNull(txOut)

        val packet = Packet.fromScript(txOut.publicKeyScript.toByteArray())
        assertEquals(1, packet.groups.size)
        assertEquals(1, packet.groups[0].inputs.size)
        assertEquals(2, packet.groups[0].outputs.size)
    }

    @Test
    fun should_return_no_change_when_inputs_are_fully_allocated_to_outputs() {
        val inputs =
            listOf(
                Triple(FAKE_ASSET_ID, 0, 1000L),
            )
        val outputs =
            listOf(
                Triple(FAKE_ASSET_ID, 1, 400L),
                Triple(FAKE_ASSET_ID, 2, 600L),
            )
        val txOut = AssetPacketBuilder.build(inputs, outputs, 0)

        assertNotNull(txOut)

        val packet = Packet.fromScript(txOut.publicKeyScript.toByteArray())
        assertEquals(2, packet.groups[0].outputs.size)
        assertEquals(1000, packet.groups[0].outputs.sumOf { it.amount })
    }

    @Test
    fun should_merge_change_to_existing_vout() {
        val inputs =
            listOf(
                Triple(FAKE_ASSET_ID, 0, 1000L),
            )
        val outputs =
            listOf(
                Triple(FAKE_ASSET_ID, 0, 400L),
            )
        val txOut = AssetPacketBuilder.build(inputs, outputs, 0)

        assertNotNull(txOut)

        val packet = Packet.fromScript(txOut.publicKeyScript.toByteArray())

        assertEquals(1, packet.groups[0].outputs.size)
        assertEquals(1000, packet.groups[0].outputs[0].amount)
    }

    @Test
    fun should_create_multiple_groups_for_multiple_assets() {
        val inputs =
            listOf(
                Triple(FAKE_ASSET_ID, 0, 500L),
                Triple(FAKE_ASSET_ID_2, 1, 300L),
            )

        val txOut = AssetPacketBuilder.build(inputs, listOf(), 0)

        assertNotNull(txOut)

        val packet = Packet.fromScript(txOut.publicKeyScript.toByteArray())

        assertEquals(2, packet.groups.size)
    }

    @Test
    fun group_order_is_deterministic_regardless_of_input_order() {
        val forwardInputs =
            listOf(
                Triple(FAKE_ASSET_ID, 0, 500L),
                Triple(FAKE_ASSET_ID_2, 1, 300L),
            )
        val reversedInputs =
            listOf(
                Triple(FAKE_ASSET_ID_2, 1, 300L),
                Triple(FAKE_ASSET_ID, 0, 500L),
            )

        val forwardTxOut = assertNotNull(AssetPacketBuilder.build(forwardInputs, listOf(), 0))
        val reversedTxOut = assertNotNull(AssetPacketBuilder.build(reversedInputs, listOf(), 0))

        assertEquals(forwardTxOut.publicKeyScript.toHex(), reversedTxOut.publicKeyScript.toHex())

        val packet = Packet.fromScript(forwardTxOut.publicKeyScript.toByteArray())

        assertEquals(2, packet.groups.size)
        assertEquals(FAKE_ASSET_ID, packet.groups[0].assetId.toString())
        assertEquals(FAKE_ASSET_ID_2, packet.groups[1].assetId.toString())
    }

    @Test
    fun should_return_null_when_outputs_exceed_inputs() {
        val inputs =
            listOf(
                Triple(FAKE_ASSET_ID, 0, 500L),
            )
        val outputs =
            listOf(
                Triple(FAKE_ASSET_ID, 0, 500L),
                Triple(FAKE_ASSET_ID, 1, 300L),
            )
        assertNull(AssetPacketBuilder.build(inputs, outputs, 0))
    }

    companion object {
        private const val FAKE_ASSET_ID = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b20000"
        private const val FAKE_ASSET_ID_2 = "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c30000"
    }
}
