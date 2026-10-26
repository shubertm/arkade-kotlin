package com.arkade.core.wallet

import com.arkade.core.taproot.getTaprootScriptPubKey
import com.arkade.core.wallet.signer.Signer
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
import kotlin.test.assertTrue

open class SignerTest {
    suspend fun testSigningTransaction(
        descriptor: String,
        signer: Signer,
    ) {
        val (outputKey, _) = signer.xOnlyPublicKey(descriptor).outputKey(Crypto.TaprootTweak.KeyPathTweak)
        val scriptPubKey = getTaprootScriptPubKey(outputKey.value.toByteArray())

        val prevOut =
            TxOut(
                Satoshi(10000),
                scriptPubKey,
            )
        val prevTx =
            Transaction(
                3,
                listOf(),
                listOf(prevOut),
                0,
            )

        val input =
            TxIn(
                OutPoint(prevTx.txid, 0),
                0,
            )
        val output =
            TxOut(
                Satoshi(6000),
                scriptPubKey,
            )
        val tx =
            Transaction(
                3,
                listOf(input),
                listOf(output),
                0,
            )

        val psbt =
            with(Psbt(tx)) {
                updateWitnessInput(
                    input.outPoint,
                    prevOut,
                    sighashType = SigHash.SIGHASH_ALL,
                    taprootInternalKey = signer.xOnlyPublicKey(descriptor),
                )
            }.right!!

        val signedTx = signer.sign(descriptor, psbt, arrayOf(0))

        signedTx.correctlySpends(listOf(prevTx), ScriptFlags.SCRIPT_VERIFY_TAPROOT)
    }

    suspend fun testSigningMessageUsingSchnorr(
        descriptor: String,
        signer: Signer,
    ) {
        val message = sha256("message".encodeToByteArray())
        val signature = signer.signMessage(descriptor, message)
        assertTrue {
            Crypto.verifySignatureSchnorr(ByteVector32(message), ByteVector(signature), signer.xOnlyPublicKey(descriptor))
        }
    }
}
