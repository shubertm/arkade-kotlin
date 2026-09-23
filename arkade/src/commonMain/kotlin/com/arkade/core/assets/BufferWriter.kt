package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayOutput

/**
 * Writes [value] as an unsigned 16-bit integer encoded as two little-endian bytes.
 *
 * @param value The value to write; must fit in an unsigned 16-bit integer.
 * @throws IllegalArgumentException if [value] is outside the range `0..0xFFFF`.
 */
fun ByteArrayOutput.writeUInt16LE(value: Int) {
    require(value in 0..0xFFFF) { "Value out of uint16 range: $value" }
    write(value.toByte().toInt())
    write((value shr 8).toByte().toInt())
}

fun ByteArrayOutput.writeVarInt(value: Long) {
    do {
        var byte = (value and 0x7F).toByte()
        value ushr 7
        if (value > 0L) {
            byte = (byte.toInt() or 0x80).toByte()
        }
        write(byte.toInt())
    } while (value > 0L)
}

fun ByteArrayOutput.writeBytes(bytes: ByteArray) {
    write(bytes)
}

fun ByteArrayOutput.writeVarBytes(bytes: ByteArray) {
    writeVarInt(bytes.size.toLong())
    writeBytes(bytes)
}
