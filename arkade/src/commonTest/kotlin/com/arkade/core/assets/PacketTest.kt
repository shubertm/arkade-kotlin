package com.arkade.core.assets

import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OP_RETURN
import fr.acinq.bitcoin.Script
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PacketTest {
    @Test
    fun serialize_packet_data_for_self_controlled_issuance() {
        val controlRef = AssetRef.fromGroupIndex(0)
        val outputs =
            listOf(
                AssetOutput.create(0, 21_000_000),
            )
        val group = AssetGroup.create(null, controlRef, listOf(), outputs, listOf())
        val packet = Packet.create(listOf(group))
        assertEquals("01020200000001010000c0de810a", packet.toString())
    }

    @Test
    fun serialize_packet_data_for_many_controlled_issuance() {
        val group0 =
            AssetGroup.create(
                null,
                AssetRef.fromGroupIndex(3),
                listOf(),
                listOf(
                    AssetOutput.create(1, 100),
                ),
                listOf(
                    AssetMetadata.create("ticker", "TEST"),
                ),
            )
        val group1 =
            AssetGroup.create(
                null,
                AssetRef.fromGroupIndex(3),
                listOf(),
                listOf(
                    AssetOutput.create(1, 300),
                ),
                listOf(
                    AssetMetadata.create("ticker", "TEST2"),
                ),
            )
        val group2 =
            AssetGroup.create(
                null,
                AssetRef.fromGroupIndex(3),
                listOf(),
                listOf(
                    AssetOutput.create(0, 2100),
                ),
                listOf(
                    AssetMetadata.create("ticker", "TEST3"),
                ),
            )
        val group3 =
            AssetGroup.create(
                null,
                null,
                listOf(),
                listOf(
                    AssetOutput.create(2, 1),
                ),
                listOf(
                    AssetMetadata.create("ticker", "TEST3"),
                    AssetMetadata.create("desc", "control_asset"),
                ),
            )
        val packet = Packet.create(listOf(group0, group1, group2, group3))
        assertEquals(
            "040602030001067469636b657204544553540001010100640602030001067469636b65720554455354320001010100ac020602030001067469636b65720554455354330001010000b4100402067469636b657205544553543304646573630d636f6e74726f6c5f6173736574000101020001",
            packet.toString(),
        )
    }

    @Test
    fun serialize_should_produce_extension_wrapped_script() {
        val controlRef = AssetRef.fromGroupIndex(0)
        val outputs = listOf(AssetOutput.create(0, 21000000))
        val group = AssetGroup.create(null, controlRef, listOf(), outputs, listOf())
        val packet = Packet.create(listOf(group))
        val serialized = packet.serialize()
        assertTrue(Extension.isExtension(serialized))

        val deserializedExtension = Extension.fromScript(serialized)
        val packetFromDeserializedExt = deserializedExtension.getAssetPacket()
        assertNotNull(packetFromDeserializedExt)
        assertEquals(1, packetFromDeserializedExt.groups.size)
    }

    @Test
    fun should_parse_raw_hex_packet_data() {
        val packet = Packet.fromString("01020200000001010000c0de810a")
        assertEquals(1, packet.groups.size)
        assertTrue(packet.groups[0].isIssuance)
    }

    @Test
    fun should_parse_raw_bytes_packet_data() {
        val packet = Packet.fromBytes("01020200000001010000c0de810a".hexToByteArray())
        assertEquals(1, packet.groups.size)
    }

    @Test
    fun simple_transfer_round_trips() {
        val assetId = AssetId.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0)
        val group =
            AssetGroup.create(
                assetId,
                null,
                listOf(
                    AssetInput.create(0, 100),
                ),
                listOf(
                    AssetOutput.create(0, 50),
                    AssetOutput.create(1, 50),
                ),
                listOf(),
            )
        val packet = Packet.create(listOf(group))
        val deserialized = Packet.fromScript(packet.toTxOut().publicKeyScript.toByteArray())
        assertEquals(1, deserialized.groups.size)
        assertEquals(100, deserialized.groups[0].inputs[0].amount)
        assertEquals(50, deserialized.groups[0].outputs[0].amount)
        assertEquals(50, deserialized.groups[0].outputs[1].amount)
    }

    @Test
    fun valid_extension_script_should_return_is_extension_true() {
        val assetId = AssetId.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0)
        val group =
            AssetGroup.create(
                assetId,
                null,
                listOf(
                    AssetInput.create(0, 1),
                ),
                listOf(
                    AssetOutput.create(0, 1),
                ),
                listOf(),
            )
        val packet = Packet.create(listOf(group))
        assertTrue(Extension.isExtension(packet.serialize()))
    }

    @Test
    fun no_asset_extension_script_should_return_false() {
        val script =
            Script.write(
                listOf(
                    OP_RETURN,
                    OP_PUSHDATA(byteArrayOf(0x01, 0x02, 0x03)),
                ),
            )

        assertFalse(Extension.isExtension(script))
    }

    @Test
    fun empty_extension_script_should_return_false() {
        assertFalse(Extension.isExtension(byteArrayOf()))
    }

    @Test
    fun tx_out_has_zero_amount() {
        val assetId = AssetId.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0)
        val group =
            AssetGroup.create(
                assetId,
                null,
                listOf(
                    AssetInput.create(0, 1),
                ),
                listOf(
                    AssetOutput.create(0, 1),
                ),
                listOf(),
            )
        val packet = Packet.create(listOf(group))
        assertEquals(0, packet.toTxOut().amount.sat)
    }

    @Test
    fun should_fail_creating_packet_with_empty_groups() {
        assertFailsWith<IllegalArgumentException> {
            Packet.create(listOf())
        }
    }

    @Test
    fun should_fail_creating_packet_with_invalid_control_asset_group_index() {
        val group =
            AssetGroup.create(
                null,
                AssetRef.fromGroupIndex(1),
                listOf(),
                listOf(
                    AssetOutput.create(0, 1),
                ),
                listOf(),
            )
        assertFailsWith<IllegalArgumentException> {
            Packet.create(listOf(group))
        }
    }

    @Test
    fun should_fail_creating_packet_with_duplicate_asset_id() {
        val assetId = AssetId.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0)

        val group =
            AssetGroup.create(
                assetId,
                null,
                listOf(
                    AssetInput.create(0, 100),
                ),
                listOf(
                    AssetOutput.create(0, 100),
                ),
                listOf(),
            )
        val group1 =
            AssetGroup.create(
                assetId,
                null,
                listOf(
                    AssetInput.create(0, 100),
                ),
                listOf(
                    AssetOutput.create(1, 100),
                ),
                listOf(),
            )

        val throwable =
            assertFailsWith<IllegalArgumentException> {
                Packet.create(listOf(group, group1))
            }
        assertEquals("Duplicate asset group for asset: $assetId", throwable.message)
    }

    @Test
    fun serialize_packet_data_to_raw_hex() {
        val controlRef = AssetRef.fromGroupIndex(0)
        val outputs =
            listOf(
                AssetOutput.create(0, 21_000_000),
            )
        val group =
            AssetGroup.create(
                null,
                controlRef,
                listOf(),
                outputs,
                listOf(),
            )
        val packet = Packet.create(listOf(group))
        assertEquals("01020200000001010000c0de810a", packet.toString())
    }

    @Test
    fun should_fail_deserializing_empty_string() {
        val throwable =
            assertFailsWith<IllegalArgumentException> {
                Packet.fromString("")
            }
        assertEquals("Missing packet data", throwable.message)
    }

    @Test
    fun should_fail_deserializing_invalid_hex_string() {
        val throwable =
            assertFailsWith<IllegalArgumentException> {
                Packet.fromString("invalid")
            }
        assertEquals("Invalid packet format, must be hex", throwable.message)
    }
}
