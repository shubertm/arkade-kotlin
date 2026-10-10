package com.arkade.core.assets

/**
 * Fallback [ExtensionPacket] representation for packet types not recognized by
 * [Extension.fromPayload]/[Extension.fromScript].
 *
 * Preserves the packet's raw [type] and body [data] verbatim so that round-tripping an
 * [Extension] containing unrecognized packets does not lose information.
 *
 * @property type The unrecognized packet type byte.
 * @property data The packet's raw, unparsed body bytes.
 */
class UnknownPacket(
    override val type: Byte,
    val data: ByteArray,
) : ExtensionPacket {
    /** Performs no checks because the packet body's format is unknown. */
    override fun validate() = Unit

    /** Returns [data] unchanged. */
    override fun serializePacketData(): ByteArray = data
}
