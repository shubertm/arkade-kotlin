package com.arkade.core.wallet

import com.arkade.core.bitcoin.finalizeWitnessInputs
import com.arkade.core.bitcoin.getFullySignedTx
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
        val prevOut1 =
            TxOut(
                Satoshi(10000),
                scriptPubKey,
            )
        val prevTx =
            Transaction(
                3,
                listOf(),
                listOf(prevOut, prevOut1),
                0,
            )

        val input =
            TxIn(
                OutPoint(prevTx.txid, 0),
                0,
            )
        val input1 =
            TxIn(
                OutPoint(prevTx.txid, 1),
                0,
            )

        val output =
            TxOut(
                Satoshi(6000),
                scriptPubKey,
            )
        val output1 =
            TxOut(
                Satoshi(12000),
                scriptPubKey,
            )
        val tx =
            Transaction(
                3,
                listOf(input, input1),
                listOf(output, output1),
                0,
            )

        var psbt = Psbt(tx)
        tx.txIn.forEach { input ->
            psbt =
                psbt
                    .updateWitnessInput(
                        input.outPoint,
                        prevTx.txOut[input.outPoint.index.toInt()],
                        sighashType = SigHash.SIGHASH_ALL,
                        taprootInternalKey = signer.xOnlyPublicKey(descriptor),
                    ).right!!
        }

        var signedTx = signer.sign(descriptor, psbt, arrayOf(0, 1))

        signedTx = signedTx.finalizeWitnessInputs(listOf(0, 1))

        Transaction.correctlySpends(signedTx.getFullySignedTx(), prevTx, ScriptFlags.MANDATORY_SCRIPT_VERIFY_FLAGS)
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
