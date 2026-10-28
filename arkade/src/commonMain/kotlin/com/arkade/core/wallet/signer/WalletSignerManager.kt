package com.arkade.core.wallet.signer

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.psbt.Psbt

/**
 * Exposes signing capabilities backed by a wallet's [Signer].
 *
 * Implemented by [com.arkade.core.wallet.Wallet] to delegate signing operations to the
 * wallet's configured [signer].
 */
interface WalletSignerManager {
    /** The [Signer] used to fulfill [sign] and [signMessage] requests. */
    val signer: Signer

    /**
     * Signs [psbt] using [signer] for the given [descriptor].
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param psbt The PSBT to sign.
     * @param inputIndexes The indexes of the inputs to sign; if empty, all inputs are signed.
     * @return The PSBT with all requested inputs signed.
     */
    suspend fun sign(
        descriptor: String,
        psbt: Psbt,
        inputIndexes: Array<Int>,
    ): Psbt

    /**
     * Signs [psbt] using [signer] for the given [descriptor].
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param psbt The PSBT to sign.
     * @param outpoints The outpoints of the inputs to sign; if empty, all inputs are signed.
     * @return The PSBT with all requested inputs signed.
     */
    suspend fun sign(
        descriptor: String,
        psbt: Psbt,
        outpoints: Array<OutPoint>,
    ): Psbt

    /**
     * Signs [message] using [signer] for the given [descriptor].
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param message The message bytes to sign.
     * @return The resulting signature bytes.
     */
    suspend fun signMessage(
        descriptor: String,
        message: ByteArray,
    ): ByteArray
}
