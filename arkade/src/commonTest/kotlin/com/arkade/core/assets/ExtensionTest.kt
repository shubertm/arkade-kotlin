package com.arkade.core.assets

import com.arkade.readJsonFile
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionTest {
    private val extensions =
        Json.parseToJsonElement(readJsonFile("fixtures/assets/extension-fixtures.json"))

    @Test
    fun create_extension_from_valid_bytes() {
        val validExtensions =
            extensions.jsonObject["valid"]?.jsonObject["newExtensionFromBytes"]?.jsonArray
        validExtensions?.forEach {
            val name = it.jsonObject["name"]?.jsonPrimitive?.content
            val hex = it.jsonObject["hex"]?.jsonPrimitive?.content!!
            val expectedCount = it.jsonObject["expectedPacketCount"]?.jsonPrimitive?.int

            val script = Script.write(Script.parse(hex))
            val extension = Extension.fromScript(script)

            assertEquals(expectedCount, extension.packets.size)

            val expectedPacketTypes = it.jsonObject["expectedPacketTypes"]?.jsonArray

            if (expectedPacketTypes != null) {
                extension.packets.forEachIndexed { index, packet ->
                    assertEquals(
                        expectedPacketTypes[index].jsonPrimitive.int.toByte(),
                        packet.type,
                        "$name packet[$index] type mismatch",
                    )
                }
            }
        }
    }

    @Test
    fun valid_round_trip() {
        val validExtensions =
            extensions.jsonObject["valid"]?.jsonObject["roundtrip"]?.jsonArray
        validExtensions?.forEach {
            val name = it.jsonObject["name"]?.jsonPrimitive?.content
            val hex = it.jsonObject["hex"]?.jsonPrimitive?.content!!

            val extension = Extension.fromScript(hex.hexToByteArray())

            val serializedExtension = extension.serialize()
            val serializedHex = Hex.encode(serializedExtension)

            assertEquals(
                hex,
                serializedHex,
                "$name round trip mismatch",
            )
            assertTrue(
                Extension.isExtension(serializedExtension),
                "$name isExtension should be true after round trip",
            )

            val txOut = extension.toTransactionOutput()
            assertNotNull(txOut, "$name Transaction output should not be null")
            assertEquals(
                hex,
                txOut.publicKeyScript.toByteArray().toHexString(),
                "$name Transaction output script mismatch",
            )
        }
    }

    @Test
    fun is_extension_true() {
        val validExtensions =
            extensions.jsonObject["isExtension"]?.jsonObject["true"]?.jsonArray
        validExtensions?.forEach {
            val name = it.jsonObject["name"]?.jsonPrimitive?.content
            val hex = it.jsonObject["hex"]?.jsonPrimitive?.content!!
            val script = Script.write(Script.parse(hex))
            assertTrue(
                Extension.isExtension(script),
                "$name should be an extension",
            )
        }
    }

    @Test
    fun is_extension_false() {
        val validExtensions =
            extensions.jsonObject["isExtension"]?.jsonObject["false"]?.jsonArray

        validExtensions?.forEach {
            val name = it.jsonObject["name"]?.jsonPrimitive?.content
            val hex = it.jsonObject["hex"]?.jsonPrimitive?.content!!
            assertFalse(
                Extension.isExtension(hex.hexToByteArray()),
                "$name should not be an extension",
            )
        }
    }

    @Test
    fun should_fail_creating_extension_from_invalid_bytes() {
        val invalidExtensions =
            extensions.jsonObject["invalid"]?.jsonObject["newExtensionFromBytes"]?.jsonArray
        invalidExtensions?.forEach {
            val name = it.jsonObject["name"]?.jsonPrimitive?.content
            val hex = it.jsonObject["hex"]?.jsonPrimitive?.content!!
            val expectedError = it.jsonObject["expectedError"]?.jsonPrimitive?.content!!
            val dataBytes = hex.hexToByteArray()

            val error =
                assertFailsWith<IllegalArgumentException> {
                    Extension.fromScript(dataBytes)
                }

            assertEquals(
                expectedError,
                error.message,
                "$name error mismatch: got ${error.message}",
            )
        }
    }

    @Test
    fun create_extension_from_valid_tx() {
        val validExtensions =
            extensions.jsonObject["valid"]?.jsonObject["newExtensionFromTx"]?.jsonArray
        validExtensions?.forEach {
            val name = it.jsonObject["name"]?.jsonPrimitive?.content
            val hex = it.jsonObject["hex"]?.jsonPrimitive?.content!!
            val expectedCount = it.jsonObject["expectedPacketCount"]?.jsonPrimitive?.int

            val tx = Transaction.read(hex)
            val extension = Extension.fromTransaction(tx)
            assertNotNull(extension, "$name should find extension in the transaction")
            assertEquals(
                expectedCount,
                extension.packets.size,
                "$name packet count mismatch",
            )
        }
    }

    @Test
    fun should_fail_creating_extension_from_invalid_tx() {
        val invalidExtensions =
            extensions.jsonObject["invalid"]?.jsonObject["newExtensionFromTx"]?.jsonArray

        invalidExtensions?.forEach {
            val name = it.jsonObject["name"]?.jsonPrimitive?.content
            val hex = it.jsonObject["hex"]?.jsonPrimitive?.content!!
            val expectedError = it.jsonObject["expectedError"]?.jsonPrimitive?.content!!
            val tx = Transaction.read(hex)

            if (expectedError == "ErrExtensionNotFound") {
                val error =
                    assertNull(
                        Extension.fromTransaction(tx),
                        "$name should not find extension in the transaction",
                    )
            } else {
                assertFailsWith<IllegalArgumentException> {
                    Extension.fromTransaction(tx) ?: throw IllegalArgumentException(expectedError)
                }
            }
        }
    }

    @Test
    fun should_return_data_from_get_asset_packet() {
        val assetGroup =
            AssetGroup.create(
                null,
                AssetRef.fromGroupIndex(0),
                listOf(),
                listOf(AssetOutput.create(0, 100)),
                listOf(),
            )

        val packet = Packet.create(listOf(assetGroup))
        val extension = Extension(listOf(packet))

        val assetPacket = assertNotNull(extension.getAssetPacket())
        assertEquals(1, assetPacket.groups.size)
    }

    @Test
    fun should_return_null_from_absent_asset_packet() {
        val extension =
            Extension(
                listOf(
                    UnknownPacket(0xFF.toByte(), byteArrayOf(0xDE.toByte(), 0xAD.toByte())),
                ),
            )
        assertNull(extension.getAssetPacket())
    }
}
