package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.readNBytes

/**
 * Reads a Bitcoin-style LEB128 variable-length integer from this buffer.
 *
 * Each byte contributes its low 7 bits to the result, most-significant-group-first is not used;
 * instead groups are read least-significant-first with the high bit of each byte signaling
 * whether another byte follows. As a special case, once 63 bits have been accumulated, exactly
 * one more byte is read, and it must equal `0x01`, enforcing a canonical encoding for the top bit
 * of a 64-bit value.
 *
 * @return The decoded unsigned 64-bit integer.
 * @throws IllegalStateException if the input ends before a complete var-int is read.
 * @throws IllegalArgumentException if the final byte at bit position 63 is not `0x01`
 * (non-canonical or oversized encoding).
 */
fun ByteArrayInput.readVarInt(): ULong {
    var result = 0UL
    var shift = 0
    var byte: Int
    while (true) {
        byte = read()
        require(byte != -1) { "Unexpected end of input" }

        if (shift == 63) {
            require(byte == 0x01) { "VarInt is too big, non-canonical or contains unwanted trailing bits" }
            result = result or (byte.toULong() shl 63)
            return result
        }

        result = result or (((byte and 0x7F).toULong() shl shift))

        if ((byte and 0x80) == 0) return result

        shift += 7
    }
}

/**
 * Reads a [readVarInt] value and narrows it to an [Int].
 *
 * @return The decoded value as an [Int].
 * @throws IllegalArgumentException if the decoded value exceeds [Int.MAX_VALUE].
 */
fun ByteArrayInput.readVarIntToInt(): Int {
    val varInt = readVarInt()
    require(varInt <= Int.MAX_VALUE.toULong()) { "VarInt is too big, expected $varInt <= ${Int.MAX_VALUE}" }
    return varInt.toInt()
}

/**
 * Reads a [readVarInt] value and narrows it to a [Long].
 *
 * @return The decoded value as a [Long].
 * @throws IllegalArgumentException if the decoded value exceeds [Long.MAX_VALUE].
 */
fun ByteArrayInput.readVarIntToLong(): Long {
    val varInt = readVarInt()
    require(varInt <= Long.MAX_VALUE.toULong()) { "VarInt is too big, expected $varInt <= ${Long.MAX_VALUE}" }
    return varInt.toLong()
}

/**
 * Reads a length-prefixed byte array: a [readVarIntToInt] size followed by that many bytes.
 *
 * @return The read bytes, or `null` if the underlying buffer could not supply the requested
 * number of bytes.
 * @throws IllegalArgumentException if the decoded size is negative or exceeds the number of
 * bytes remaining in this buffer.
 */
fun ByteArrayInput.readVarBytes(): ByteArray? {
    val size = readVarIntToInt()
    require(size in 0..availableBytes) { "Invalid size for var bytes: $size" }
    return readNBytes(size)
}

/**
 * Reads an unsigned 16-bit integer encoded as two little-endian bytes.
 *
 * @return The decoded value, in the range `0..0xFFFF`.
 * @throws IllegalStateException if fewer than 2 bytes remain in this buffer.
 */
fun ByteArrayInput.readUInt16LE(): Int {
    val data = requireNotNull(readNBytes(2))
    require(data.size == 2) { "Unexpected end of input" }
    val low = data[0].toInt() and 0xFF
    val high = data[1].toInt() and 0xFF
    return low or (high shl 8)
}
