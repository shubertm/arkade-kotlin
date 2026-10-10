package com.arkade.core.assets

/**
 * A single packet within an [Extension] payload.
 *
 * Each packet is identified by a [type] byte that is unique within its containing [Extension],
 * and knows how to serialize its own body via [serializePacketData].
 */
interface ExtensionPacket {
    /** The packet type byte identifying this packet's format within an [Extension]. */
    val type: Byte

    /**
     * Checks the constraints defined by this packet type; unknown packet bodies are not checked.
     *
     * @throws IllegalArgumentException if a known packet violates its validation constraints.
     */
    fun validate()

    /** Serializes this packet's body, excluding the leading [type] byte and length prefix. */
    fun serializePacketData(): ByteArray
}
