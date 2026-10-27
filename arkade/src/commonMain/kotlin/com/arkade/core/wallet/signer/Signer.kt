package com.arkade.core.wallet.signer

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.bitcoin.crypto.musig2.SecretNonce
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.getOrElse

/**
 * Signing abstraction for a wallet's key material.
 *
 * A [Signer] knows how to sign PSBT transaction inputs and arbitrary messages for a given
 * output descriptor, and how to expose the public key material needed to build such
 * descriptors.
 */
interface Signer {
    /**
     * Signs the given [psbt] using the key derived for [descriptor].
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param psbt The PSBT to sign.
     * @param inputIndexes The indexes of the inputs to sign; if empty, all inputs are signed.
     * @return The fully signed [Transaction].
     */
    suspend fun sign(
        descriptor: String,
        psbt: Psbt,
        inputIndexes: Array<Int>,
    ): Psbt

    /**
     * Signs the given [psbt] using the key derived for [descriptor].
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param psbt The PSBT to sign.
     * @param outpoints The outpoints of the inputs to sign; if empty, all inputs are signed.
     * @return The fully signed [Transaction].
     */
    suspend fun sign(
        descriptor: String,
        psbt: Psbt,
        outpoints: Array<OutPoint>,
    ): Psbt

    /**
     * Signs an arbitrary [message] using the key derived for [descriptor].
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param message The message bytes to sign.
     * @param signatureType The signature scheme to use, defaults to [SignatureType.SCHNORR].
     * @return The resulting signature bytes.
     */
    suspend fun signMessage(
        descriptor: String,
        message: ByteArray,
        signatureType: SignatureType = SignatureType.SCHNORR,
    ): ByteArray

    /**
     * Creates a new [SignerSession].
     *
     * @return A new [SignerSession] instance.
     */
    suspend fun signerSession(): SignerSession

    /**
     * Derives the x-only public key associated with [descriptor].
     *
     * @param descriptor The output descriptor identifying which key to derive.
     * @return The corresponding [XonlyPublicKey].
     */
    suspend fun xOnlyPublicKey(descriptor: String): XonlyPublicKey

    /**
     * Returns this signer's account-level output descriptor.
     *
     * @return The account descriptor string.
     */
    fun accountDescriptor(): String

    /**
     * Generates a MuSig2 nonce pair for the key derived for [descriptor], to be used as this
     * signer's contribution to a cooperative signing session.
     *
     * @param sessionId A value unique to this signing session, used to derive the nonce
     * alongside the signing key; must not be reused across independent sessions for the same key.
     * @param descriptor The output descriptor identifying which key to generate a nonce for.
     * @param pubKeys The public keys of all participants in the signing session, including this
     * signer's own public key.
     * @param message The message (typically a sighash) that will be signed, if already known at
     * nonce generation time; `null` if it will only be known later.
     * @param extraInput Additional randomness to mix into nonce generation, if any.
     * @return A pair of the secret nonce, which must be kept private and used only once for
     * [signMusig], and the corresponding public nonce, which is shared with the other
     * participants.
     */
    suspend fun generateNonce(
        sessionId: ByteVector32,
        descriptor: String,
        pubKeys: List<PublicKey>,
        message: ByteVector32?,
        extraInput: ByteVector32? = null,
    ): Pair<SecretNonce, IndividualNonce>

    /**
     * Produces this signer's MuSig2 partial signature for input [inputIndex] of [tx], using the
     * key derived for [descriptor].
     *
     * [tx], [inputs], [inputIndex], [pubKeys], and [pubNonces] must all be consistent with one
     * another and with the values used to obtain [privNonce] from [generateNonce]: [inputs] must
     * be the previous outputs spent by every input of [tx] (in order), [pubKeys] must be the same
     * set of participant public keys used to generate [privNonce], and [pubNonces] must contain
     * the aggregated/individual public nonces of all participants for this same session and
     * input.
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param tx The transaction being signed.
     * @param inputs The previous outputs spent by each of [tx]'s inputs, in input order.
     * @param inputIndex The index of the input to produce a partial signature for.
     * @param privNonce This signer's secret nonce, as previously returned by [generateNonce];
     * must be used for exactly one [signMusig] call.
     * @param pubKeys The public keys of all participants in the signing session.
     * @param pubNonces The public nonces of all participants for this input.
     * @param scriptTree The Taproot script tree being spent from, if this is a script-path spend;
     * `null` for a key-path spend.
     * @return This signer's partial signature over the input.
     */
    suspend fun signMusig(
        descriptor: String,
        tx: Transaction,
        inputs: List<TxOut>,
        inputIndex: Int,
        privNonce: SecretNonce,
        pubKeys: List<PublicKey>,
        pubNonces: List<IndividualNonce>,
        scriptTree: ScriptTree?,
    ): ByteVector32
}

/**
 * Base [Signer] implementation shared by concrete signer types.
 *
 * Implements PSBT input signing and message signing on top of a single [privateKey], which
 * subclasses are responsible for deriving and keeping up to date for the descriptor currently
 * being operated on.
 */
abstract class
SignerImpl : Signer {
    /**
     * The private key currently used for signing and public key derivation. Subclasses must
     * ensure this is set to the key corresponding to the descriptor being operated on before
     * delegating to this class's implementations.
     */
    protected abstract var privateKey: PrivateKey

    /**
     * Signs the requested inputs of [psbt] with [privateKey].
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param psbt The PSBT to sign.
     * @param inputIndexes The indexes of the inputs to sign; if empty, all inputs are signed.
     * @return The fully signed [Transaction].
     * @throws IllegalStateException if signing any of the targeted inputs fails.
     */
    override suspend fun sign(
        descriptor: String,
        psbt: Psbt,
        inputIndexes: Array<Int>,
    ): Psbt {
        var signedTx = psbt
        val indices =
            if (inputIndexes.isEmpty()) {
                signedTx.inputs.indices
            } else {
                inputIndexes.asList()
            }

        signedTx = signAll(signedTx, indices)
        return signedTx
    }

    /**
     * Signs the requested inputs of [psbt] with [privateKey], identified by their outpoints.
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param psbt The PSBT to sign.
     * @param outpoints The outpoints of the inputs to sign; if empty, all inputs are signed.
     * @return The fully signed [Transaction].
     * @throws IllegalStateException if signing any of the targeted inputs fails.
     */
    override suspend fun sign(
        descriptor: String,
        psbt: Psbt,
        outpoints: Array<OutPoint>,
    ): Psbt {
        var signedTx = psbt
        if (outpoints.isEmpty()) {
            signedTx = signAll(signedTx)
            return signedTx
        }

        outpoints.forEach { outpoint ->
            val result =
                signedTx.sign(privateKey, outpoint).getOrElse {
                    throw IllegalStateException("Failed to sign transaction")
                }
            signedTx = result.psbt
        }
        return signedTx
    }

    /**
     * Signs [message] with [privateKey] using the requested [signatureType].
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param message The message bytes to sign.
     * @param signatureType The signature scheme to use.
     * @return The resulting signature bytes.
     * @throws IllegalArgumentException if [signatureType] is [SignatureType.SCHNORR] and
     * [message] is not exactly 32 bytes long.
     */
    override suspend fun signMessage(
        descriptor: String,
        message: ByteArray,
        signatureType: SignatureType,
    ): ByteArray =
        when (signatureType) {
            SignatureType.SCHNORR -> {
                require(message.size == 32) { "Invalid message size for Schnorr signing: ${message.size}" }
                Crypto
                    .signSchnorr(
                        ByteVector32(message),
                        privateKey,
                        taprootTweak = null,
                        auxrand32 = null,
                    ).toByteArray()
            }
            SignatureType.ECDSA -> Crypto.sign(message, privateKey).toByteArray()
        }

    /**
     * Derives the x-only public key of [privateKey].
     *
     * @param descriptor The output descriptor identifying which key to derive.
     * @return The corresponding [XonlyPublicKey].
     */
    override suspend fun xOnlyPublicKey(descriptor: String): XonlyPublicKey = privateKey.xOnlyPublicKey()

    /**
     * Generates a MuSig2 nonce pair for [privateKey].
     *
     * @param sessionId A value unique to this signing session, used to derive the nonce
     * alongside [privateKey]; must not be reused across independent sessions for the same key.
     * @param descriptor The output descriptor identifying which key to generate a nonce for.
     * @param pubKeys The public keys of all participants in the signing session, including this
     * signer's own public key.
     * @param message The message (typically a sighash) that will be signed, if already known at
     * nonce generation time; `null` if it will only be known later.
     * @param extraInput Additional randomness to mix into nonce generation, if any.
     * @return A pair of the secret nonce, which must be kept private and used only once for
     * [signMusig], and the corresponding public nonce, which is shared with the other
     * participants.
     */
    override suspend fun generateNonce(
        sessionId: ByteVector32,
        descriptor: String,
        pubKeys: List<PublicKey>,
        message: ByteVector32?,
        extraInput: ByteVector32?,
    ): Pair<SecretNonce, IndividualNonce> {
        val signingKey = Either.Left(privateKey)
        return Musig2.generateNonce(sessionId, signingKey, pubKeys, message, extraInput)
    }

    /**
     * Produces a MuSig2 partial signature for input [inputIndex] of [tx] using [privateKey].
     *
     * [tx], [inputs], [inputIndex], [pubKeys], and [pubNonces] must all be consistent with one
     * another and with the values used to obtain [privNonce] from [generateNonce]: [inputs] must
     * be the previous outputs spent by every input of [tx] (in order), [pubKeys] must be the same
     * set of participant public keys used to generate [privNonce], and [pubNonces] must contain
     * the aggregated/individual public nonces of all participants for this same session and
     * input.
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param tx The transaction being signed.
     * @param inputs The previous outputs spent by each of [tx]'s inputs, in input order.
     * @param inputIndex The index of the input to produce a partial signature for.
     * @param privNonce This signer's secret nonce, as previously returned by [generateNonce];
     * must be used for exactly one [signMusig] call.
     * @param pubKeys The public keys of all participants in the signing session.
     * @param pubNonces The public nonces of all participants for this input.
     * @param scriptTree The Taproot script tree being spent from, if this is a script-path spend;
     * `null` for a key-path spend.
     * @return This signer's partial signature over the input.
     * @throws Throwable the underlying error thrown by [Musig2.signTaprootInput] if partial
     * signature generation fails, e.g. due to inconsistent [inputs], [pubKeys], or [pubNonces].
     */
    override suspend fun signMusig(
        descriptor: String,
        tx: Transaction,
        inputs: List<TxOut>,
        inputIndex: Int,
        privNonce: SecretNonce,
        pubKeys: List<PublicKey>,
        pubNonces: List<IndividualNonce>,
        scriptTree: ScriptTree?,
    ): ByteVector32 =
        Musig2
            .signTaprootInput(
                privateKey,
                tx,
                inputIndex,
                inputs,
                pubKeys,
                privNonce,
                pubNonces,
                scriptTree,
            ).getOrElse { throw it }

    /**
     * Signs each of [indices] on [psbt] with [privateKey], in order, folding the result of each
     * signature into the PSBT passed to the next.
     *
     * @param psbt The PSBT to sign.
     * @param indices The input indices to sign; defaults to all of [psbt]'s inputs.
     * @return The PSBT with all requested inputs signed.
     * @throws IllegalStateException if signing any of the targeted inputs fails.
     */
    private fun signAll(
        psbt: Psbt,
        indices: Iterable<Int> = psbt.inputs.indices,
    ): Psbt {
        var signedTx = psbt
        indices.forEach { index ->
            val result =
                signedTx.sign(privateKey, index).getOrElse {
                    throw IllegalStateException("Failed to sign transaction $it")
                }
            signedTx = result.psbt
        }
        return signedTx
    }
}

/**
 * The signature scheme used when signing an arbitrary message via [Signer.signMessage].
 */
enum class SignatureType {
    /** BIP-340 Schnorr signature; requires a 32-byte message (typically a digest). */
    SCHNORR,

    /** ECDSA signature. */
    ECDSA,
}
