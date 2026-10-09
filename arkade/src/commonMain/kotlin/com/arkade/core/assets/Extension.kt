package com.arkade.core.assets

import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OP_RETURN
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.readNBytes

/**
 * An Arkade protocol extension embedded in a transaction output's `OP_RETURN` script, carrying
 * one or more [ExtensionPacket]s identified by the [ArkadeMagic] prefix.
 *
 * @property packets The packets carried by this extension, each with a unique
 * [ExtensionPacket.type].
 */
class Extension(
    val packets: List<ExtensionPacket>,
) {
    /** Returns the asset [Packet] carried by this extension, or `null` if none is present. */
    fun getAssetPacket(): Packet? {
        packets.forEach { packet ->
            if (packet is Packet) return packet
        }
        return null
    }

    fun serialize(): ByteArray {
        val output = ByteArrayOutput()
        output.write(ArkadeMagic)
        packets.forEach { packet ->
            val packetData = packet.serializePacketData()
            output.write(packet.type.toInt())
            output.writeVarBytes(packetData)
        }
        return buildOpReturnScript(output.toByteArray())
    }

    fun toTransactionOutput(): TxOut {
        val scriptPubKey = serialize()
        return TxOut(Satoshi(0), scriptPubKey)
    }

    companion object {
        private val ArkadeMagic = byteArrayOf(0x41, 0x52, 0x4B)

        /**
         * Checks whether [script] looks like an Arkade extension script, i.e. an `OP_RETURN`
         * followed by an `OP_PUSHDATA` whose data starts with [ArkadeMagic].
         *
         * @param script The raw output script to inspect.
         * @return `true` if [script] parses and matches the expected prefix; `false` if it fails
         * to parse, is too short, does not start with `OP_RETURN`, or its data does not start
         * with [ArkadeMagic]. This function never throws.
         */
        fun isExtension(script: ByteArray): Boolean {
            val script =
                try {
                    Script.parse(script)
                } catch (_: Exception) {
                    return false
                } // Safe to say any broken script is not an extension
            if (
                script.isEmpty() ||
                script.size < 2 ||
                script[0] != OP_RETURN
            ) {
                return false
            }
            val op = script[1] as? OP_PUSHDATA ?: return false
            val data = op.data
            if (data.size() == 0 || data.size() < ArkadeMagic.size) return false

            return data.take(ArkadeMagic.size).contentEquals(ArkadeMagic)
        }

        /**
         * Parses an [Extension] from a raw `OP_RETURN` output [script].
         *
         * @param script The raw output script, expected to start with `OP_RETURN`.
         * @return The parsed [Extension].
         * @throws IllegalArgumentException if [script] does not parse, is empty, or does not
         * start with `OP_RETURN`, or if the concatenated `OP_PUSHDATA` payload is not a valid
         * extension payload (see [fromPayload]).
         */
        fun fromScript(script: ByteArray): Extension {
            val script =
                runCatching { Script.parse(script) }.getOrElse { _ ->
                    throw IllegalArgumentException("Invalid extension script")
                }

            require(script.isNotEmpty()) { "missing OP_RETURN" }
            require(script[0] == OP_RETURN) { "Expected OP_RETURN" }

            val payload = ByteArrayOutput()
            for (i in 1 until script.size) {
                val op = script[i]
                if (op is OP_PUSHDATA) {
                    payload.write(op.data.toByteArray())
                }
            }
            return fromPayload(payload.toByteArray())
        }

        fun fromTransaction(tx: Transaction): Extension? {
            for (output in tx.txOut) {
                val scriptPubKey = output.publicKeyScript.toByteArray()
                if (isExtension(scriptPubKey)) {
                    return fromScript(scriptPubKey)
                }
            }
            return null
        }

        /**
         * Parses an [Extension] from a decoded `OP_RETURN` [payload]: the [ArkadeMagic] prefix
         * followed by a sequence of packets, each encoded as a type byte and a var-length body
         * (see [readVarBytes]).
         *
         * @param payload The raw payload bytes, excluding script opcodes.
         * @return The parsed [Extension].
         * @throws IllegalArgumentException if [payload] is shorter than [ArkadeMagic], does not
         * start with [ArkadeMagic], contains a malformed packet, contains no packets, or contains
         * two packets with the same [ExtensionPacket.type].
         */
        private fun fromPayload(payload: ByteArray): Extension {
            val payloadInput = ByteArrayInput(payload)

            require(payloadInput.availableBytes >= ArkadeMagic.size) {
                "Missing Arkade magic prefix"
            }
            val magic = payloadInput.readNBytes(ArkadeMagic.size)

            require(magic!!.contentEquals(ArkadeMagic)) {
                "Expected magic prefix ${ArkadeMagic.toHexString().lowercase()}, got ${magic.toHexString().lowercase()}"
            }

            val packets =
                runCatching {
                    val packets: MutableList<ExtensionPacket> = mutableListOf()
                    while (payloadInput.availableBytes > 0) {
                        val packetType = payloadInput.read().toByte()
                        val packetData = payloadInput.readVarBytes()
                        requireNotNull(packetData) { "Missing packet data" }
                        val packet = parsePacket(packetType, packetData)
                        packets.add(packet)
                    }
                    packets
                }.getOrElse { e ->
                    throw IllegalArgumentException("Invalid extension payload", e)
                }

            require(packets.isNotEmpty()) { "Missing packets" }

            val seenPacketsTypes: HashSet<Byte> = hashSetOf()
            packets.forEach { packet ->
                val isNotSeen = seenPacketsTypes.add(packet.type)
                require(isNotSeen) { "Duplicate packet type: ${packet.type}" }
            }
            return Extension(packets)
        }

        /**
         * Dispatches a single packet's body to the appropriate [ExtensionPacket] implementation
         * based on [packetType].
         *
         * @param packetType The packet's type byte.
         * @param packetData The packet's raw body bytes.
         * @return A [Packet] if [packetType] is [Packet.PACKET_TYPE], otherwise an
         * [UnknownPacket] wrapping [packetType] and [packetData] unchanged.
         */
        private fun parsePacket(
            packetType: Byte,
            packetData: ByteArray,
        ): ExtensionPacket =
            when (packetType) {
                Packet.PACKET_TYPE -> Packet.fromBytes(packetData)
                else -> UnknownPacket(packetType, packetData)
            }

        internal fun buildOpReturnScript(data: ByteArray): ByteArray =
            Script.write(
                listOf(
                    OP_RETURN,
                    OP_PUSHDATA(data),
                ),
            )
    }
}
