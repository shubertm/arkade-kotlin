package com.arkade.core.wallet.signer

import com.arkade.core.wallet.Wallet

/**
 * [Signer] for single-key wallets backed by an nsec-encoded private key.
 *
 * Unlike [com.arkade.core.wallet.signer.HDSigner], this signer always signs with the same
 * key regardless of the descriptor passed in, since single-key wallets have no per-index
 * derivation.
 *
 * @param secret The nsec-encoded private key to sign with.
 */
class SingleKeySigner private constructor(
    secret: String,
) : SignerImpl() {
    /** The private key decoded from the nsec [secret] provided at construction. */
    override var privateKey = Wallet.getPrivateKeyFromNSec(secret)

    /**
     * Returns this wallet's Taproot output descriptor, derived from [privateKey]'s x-only
     * public key.
     *
     * @return A descriptor in the form `tr(<xOnlyPublicKeyHex>)`.
     */
    override fun accountDescriptor(): String = "tr(${privateKey.publicKey().xOnly().value.toHex()})"

    companion object {
        /**
         * Creates a [SingleKeySigner] from an nsec-encoded private key.
         *
         * @param secret The nsec-encoded private key.
         * @return A [Signer] backed by the decoded private key.
         */
        fun fromNSec(secret: String): Signer = SingleKeySigner(secret)
    }
}
