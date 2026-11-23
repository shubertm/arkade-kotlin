package com.arkade.core

import com.arkade.core.bitcoin.Network
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.toBigDecimal
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.OP_RETURN
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.XonlyPublicKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * The unspendable x-only public key, nobody knows the private key. Any funds locked to this public key cannot be spent,
 * aka `NUMS`. In Taproot, it is used to lock key path spending and force script path spending.
 */
const val UNSPENDABLE_PUBKEY = "50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0"

fun Long.multiplyExact(other: Long): Long {
    if (this == 0L || other == 0L) return 0L
    val result = this * other
    if (result / other != this || (this == Long.MIN_VALUE && other == -1L) || (other == Long.MIN_VALUE && this == -1L)) {
        throw ArithmeticException("Long overflow")
    }
    return result
}

fun <T> Iterable<T>.sumOf(selector: (T) -> BigDecimal): BigDecimal =
    fold(0.toBigDecimal()) { sum, element ->
        sum + BigDecimal.fromBigDecimal(selector(element), sum.decimalMode)
    }

/**
 * Converts a hex string to an [XonlyPublicKey] public key
 * The hex string can be an uncompressed, compressed or x-only public key
 * @return the [XonlyPublicKey] public key
 */
fun String.toXOnlyPubKey(): XonlyPublicKey {
    val bytes = hexToByteArray()
    val bytesSize = bytes.size
    if (bytesSize == 32) {
        return XonlyPublicKey(ByteVector32(bytes))
    }
    if (bytesSize == 33 || bytesSize == 65) {
        return PublicKey.parse(bytes).xOnly()
    }
    throw IllegalArgumentException("Invalid public key: $this")
}

fun Long.toBlockHeight(): Long = this / 600

fun encodePubKeyByNetwork(
    pubKey: DeterministicWallet.ExtendedPublicKey,
    network: Network,
): String =
    when (network) {
        Network.MAINNET -> pubKey.encode(false)
        else -> pubKey.encode(true)
    }

object Json {
    class BigDecimalSerializer : KSerializer<BigDecimal> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): BigDecimal {
            val value =
                if (decoder is JsonDecoder) {
                    decoder.decodeJsonElement()
                } else {
                    decoder.decodeString()
                }
            return value.toString().removeSurrounding("\"").toBigDecimal()
        }

        override fun serialize(
            encoder: Encoder,
            value: BigDecimal,
        ) {
            if (encoder is JsonEncoder) {
                encoder.encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))
            } else {
                encoder.encodeString(value.toPlainString())
            }
        }
    }
}

fun checkSha256Hash(hash: String): Boolean {
    val regex = "^[0-9a-fA-F]{64}$".toRegex()
    return regex.matches(hash)
}

fun TxOut.isUnSpendable(): Boolean {
    val scriptPubKey =
        try {
            Script.parse(publicKeyScript)
        } catch (e: Exception) {
            throw e
        } // Better to throw than blindly assume a broken script is a spendable output
    return scriptPubKey.isNotEmpty() && scriptPubKey[0] == OP_RETURN
}

fun taggedMessageHash(
    tag: String,
    vararg data: ByteArray,
): ByteVector32 {
    var combinedData = byteArrayOf()
    data.forEach { bytes ->
        combinedData += bytes
    }
    return Crypto.taggedHash(combinedData, tag)
}

fun <K, V> MutableMap<K, V>.tryPut(
    key: K,
    value: V,
): Boolean {
    if (this.containsKey(key)) return false
    this[key] = value
    return true
}

fun <K, V> MutableMap<K, V>.tryRemove(key: K): Boolean {
    if (!this.containsKey(key)) return false
    remove(key)
    return true
}
