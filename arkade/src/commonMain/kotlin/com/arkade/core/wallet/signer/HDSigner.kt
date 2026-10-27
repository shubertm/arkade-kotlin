package com.arkade.core.wallet.signer

import com.arkade.core.bitcoin.Network
import com.arkade.core.encodePubKeyByNetwork
import com.arkade.core.wallet.Wallet.Companion.getAccountKeyPath
import com.arkade.core.wallet.Wallet.Companion.masterKeyFromSecret
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.SecretNonce
import fr.acinq.bitcoin.psbt.Psbt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [Signer] for HD (hierarchical deterministic) wallets backed by a BIP-39 mnemonic.
 *
 * Derives the account-level extended private key once at construction time, and then
 * derives a fresh child private key for each signing/derivation call based on the index
 * encoded in the given descriptor. Since [privateKey] is mutated in place before each
 * operation, access is serialized with [mutex] to prevent concurrent callers from
 * observing or using another call's derived key.
 *
 * @param mnemonics The BIP-39 mnemonic phrase used as the wallet seed.
 * @param network The [Network] used to derive the account key path and encode public keys.
 */
class HDSigner private constructor(
    mnemonics: String,
    private val network: Network,
) : SignerImpl() {
    private val mutex = Mutex()
    private val extendedKey = masterKeyFromSecret(mnemonics)
    private val accountKeyPath = getAccountKeyPath(network).first
    private val accountXPrivateKey = extendedKey.first.derivePrivateKey(accountKeyPath)
    private val receivingXPrivateKey = accountXPrivateKey.derivePrivateKey(0)

    /** The private key derived for the descriptor most recently passed to this signer. */
    override lateinit var privateKey: PrivateKey

    /**
     * Derives the child private key for [descriptor]'s index and signs [psbt] with it.
     *
     * @param descriptor The output descriptor whose trailing index identifies the child key.
     * @param psbt The PSBT to sign.
     * @param inputIndexes The indexes of the inputs to sign; if empty, all inputs are signed.
     * @return The fully signed [Transaction].
     */
    override suspend fun sign(
        descriptor: String,
        psbt: Psbt,
        inputIndexes: Array<Int>,
    ): Psbt =
        mutex.withLock {
            deriveChildPrivateKey(descriptor)
            super.sign(descriptor, psbt, inputIndexes)
        }

    override suspend fun sign(
        descriptor: String,
        psbt: Psbt,
        outpoints: Array<OutPoint>,
    ): Psbt =
        mutex.withLock {
            deriveChildPrivateKey(descriptor)
            super.sign(descriptor, psbt, outpoints)
        }

    /**
     * Derives the child private key for [descriptor]'s index and signs [message] with it.
     *
     * @param descriptor The output descriptor whose trailing index identifies the child key.
     * @param message The message bytes to sign.
     * @param signatureType The signature scheme to use.
     * @return The resulting signature bytes.
     */
    override suspend fun signMessage(
        descriptor: String,
        message: ByteArray,
        signatureType: SignatureType,
    ): ByteArray =
        mutex.withLock {
            deriveChildPrivateKey(descriptor)
            super.signMessage(descriptor, message, signatureType)
        }

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
        mutex.withLock {
            deriveChildPrivateKey(descriptor)
            super.signMusig(
                descriptor,
                tx,
                inputs,
                inputIndex,
                privNonce,
                pubKeys,
                pubNonces,
                scriptTree,
            )
        }

    override suspend fun signerSession(): SignerSession {
        TODO("Not yet implemented")
    }

    /**
     * Derives the child private key for [descriptor]'s index and returns its x-only public key.
     *
     * @param descriptor The output descriptor whose trailing index identifies the child key.
     * @return The corresponding [XonlyPublicKey].
     */
    override suspend fun xOnlyPublicKey(descriptor: String): XonlyPublicKey =
        mutex.withLock {
            deriveChildPrivateKey(descriptor)
            super.xOnlyPublicKey(descriptor)
        }

    /**
     * Builds this wallet's account-level Taproot output descriptor.
     *
     * @return A descriptor of the form `tr([<fingerprint>/86'/<coinType>'/0']<accountPublicKey>/0/‍*)`
     *
     * @throws IllegalStateException if the master key fingerprint is not 8 characters long.
     */
    override fun accountDescriptor(): String {
        val (_, coinType) = getAccountKeyPath(network)
        val accountPublicKey = encodePubKeyByNetwork(accountXPrivateKey.extendedPublicKey, network)
        val fingerprint = extendedKey.second
        require(fingerprint.length == 8) { "Invalid fingerprint length: expected 8 but is ${fingerprint.length}" }
        val accountDescriptor = "tr([$fingerprint/86'/$coinType'/0']$accountPublicKey/0/*)"
        return accountDescriptor
    }

    override suspend fun generateNonce(
        sessionId: ByteVector32,
        descriptor: String,
        pubKeys: List<PublicKey>,
        message: ByteVector32?,
        extraInput: ByteVector32?,
    ): Pair<SecretNonce, IndividualNonce> =
        mutex.withLock {
            deriveChildPrivateKey(descriptor)
            super.generateNonce(sessionId, descriptor, pubKeys, message, extraInput)
        }

    /**
     * Derives the receiving private key for the index encoded in [descriptor]'s trailing
     * path segment and stores it in [privateKey].
     *
     * Should be called before [sign], [signMessage], or any function relying on [privateKey]
     * being derived for the correct index.
     *
     * @param descriptor The output descriptor whose trailing segment (before `)`) is the
     * child index to derive, e.g. `.../0/5)`.
     * @throws IllegalArgumentException if the trailing segment of [descriptor] is not a
     * valid index.
     */
    private fun deriveChildPrivateKey(descriptor: String) {
        val childKeyIndex = descriptor.substringAfterLast('/').substringBefore(')').toLongOrNull()
        requireNotNull(childKeyIndex) { "Invalid descriptor: $descriptor" }
        privateKey = receivingXPrivateKey.derivePrivateKey(childKeyIndex).privateKey
    }

    companion object {
        /**
         * Creates an [HDSigner] from a BIP-39 mnemonic and network.
         *
         * @param mnemonics The BIP-39 mnemonic phrase used as the wallet seed.
         * @param network The [Network] used to derive the account key path and encode
         * public keys.
         * @return A [Signer] backed by the derived HD keys.
         */
        fun fromMnemonic(
            mnemonics: String,
            network: Network,
        ): Signer = HDSigner(mnemonics, network)
    }
}
