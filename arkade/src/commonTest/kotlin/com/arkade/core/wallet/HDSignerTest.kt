package com.arkade.core.wallet

import com.arkade.core.bitcoin.Network
import com.arkade.core.wallet.addresses.HDAddressProvider
import com.arkade.core.wallet.signer.HDSigner
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.bitcoin.crypto.musig2.SecretNonce
import fr.acinq.bitcoin.sat
import fr.acinq.bitcoin.utils.Either
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HDSignerTest : SignerTest() {
    private val secret = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val signer = HDSigner.fromMnemonic(secret, Network.TESTNET)
    private var lastUsedIndex = 0
    val addressProvider =
        HDAddressProvider(
            signer.accountDescriptor(),
            getLastUsedIndex = {
                lastUsedIndex
            },
            updateLastUsedIndex = {
                lastUsedIndex = it
                true
            },
        )

    @Test
    fun should_sign_transaction_successfully() {
        runTest {
            val descriptor = addressProvider.getNextSigningDescriptor()
            testSigningTransaction(descriptor, signer)
        }
    }

    @Test
    fun should_sign_message_using_schnorr_correctly() {
        runTest {
            val descriptor = addressProvider.getNextSigningDescriptor()
            testSigningMessageUsingSchnorr(descriptor, signer)
        }
    }

    @Test
    fun should_sign_transaction_with_different_descriptors() {
        runTest {
            val descriptors = hashSetOf<String>()
            for (i in 0..10) {
                val descriptor = addressProvider.getNextSigningDescriptor()
                assertTrue(descriptors.add(descriptor))
                testSigningTransaction(descriptor, signer)
            }
        }
    }

    @Test
    fun should_fail_generating_new_descriptor_when_update_last_used_index_fails() {
        runTest {
            val failingProvider =
                HDAddressProvider(
                    signer.accountDescriptor(),
                    getLastUsedIndex = { 0 },
                    updateLastUsedIndex = { false },
                )
            assertFailsWith<IllegalStateException> {
                failingProvider.getNextSigningDescriptor()
            }
        }
    }

    @Test
    fun should_generate_different_nonces_for_the_same_descriptor_and_signing_session() {
        runTest {
            var prevSecNonce: SecretNonce? = null
            var prevPubNonce: IndividualNonce? = null
            val cosigners =
                listOf(
                    PrivateKey.fromHex(Random.nextBytes(32).toHexString()).publicKey(),
                    PrivateKey.fromHex(Random.nextBytes(32).toHexString()).publicKey(),
                )
            val descriptor = addressProvider.getNextSigningDescriptor()
            val sessionId = ByteVector32.One

            for (i in 0..21) {
                val secPubNonces =
                    signer.generateNonce(
                        sessionId,
                        descriptor,
                        cosigners,
                        null,
                    )
                assertNotEquals(secPubNonces.first, prevSecNonce)
                assertNotEquals(secPubNonces.second, prevPubNonce)
                prevSecNonce = secPubNonces.first
                prevPubNonce = secPubNonces.second
            }
        }
    }

    @Test
    fun should_create_partial_musig2_signature_successfully() {
        runTest {
            val descriptor = addressProvider.getNextSigningDescriptor()
            val alicePubKey = signer.publicKey(descriptor)

            val bobPriv = PrivateKey(Random.nextBytes(32))
            val bobPubKey = bobPriv.publicKey()

            val pubKeys =
                listOf(
                    alicePubKey,
                    bobPriv.publicKey(),
                )

            val aggregatedPubKey = Musig2.aggregateKeys(pubKeys)
            val lockScript = Script.pay2tr(aggregatedPubKey, Crypto.TaprootTweak.KeyPathTweak)

            val prevTxOut = TxOut(700_000.sat(), lockScript)
            val outpoint = OutPoint(TxId(ByteVector32.One), 0)

            val inputs = listOf(TxIn(outpoint, TxIn.SEQUENCE_FINAL))
            val outputs = listOf(TxOut(500_000.sat(), Script.pay2wpkh(bobPriv.publicKey())))
            val tx =
                Transaction(
                    2,
                    inputs,
                    outputs,
                    0,
                )

            val prevTxOuts = listOf(prevTxOut)
            val sigHash = tx.hashForSigningTaprootKeyPath(0, prevTxOuts, SigHash.SIGHASH_DEFAULT)

            val (aliceSecNonce, alicePubNonce) =
                signer.generateNonce(
                    ByteVector32(Random.nextBytes(32)),
                    descriptor,
                    pubKeys,
                    sigHash,
                )

            val (bobSecNonce, bobPubNonce) =
                Musig2.generateNonce(
                    ByteVector32(Random.nextBytes(32)),
                    Either.Left(bobPriv),
                    pubKeys,
                    sigHash,
                    null,
                )

            val pubNonces = listOf(alicePubNonce, bobPubNonce)

            val alicePartialSig =
                signer.signMusig(
                    descriptor,
                    tx,
                    prevTxOuts,
                    0,
                    aliceSecNonce,
                    pubKeys,
                    pubNonces,
                    null,
                )

            val session = Musig2.taprootSession(tx, 0, prevTxOuts, pubKeys, pubNonces, null).right!!

            val isAliceSigCorrect = session.verify(alicePartialSig, alicePubNonce, alicePubKey)
            assertTrue(isAliceSigCorrect)

            val bobPartialSig = session.sign(bobSecNonce, bobPriv).right!!

            val isBobSigCorrect = session.verify(bobPartialSig, bobPubNonce, bobPubKey)
            assertTrue(isBobSigCorrect)

            val completeSig = session.aggregateSigs(listOf(alicePartialSig, bobPartialSig)).right!!

            val signedTx = tx.updateWitness(0, Script.witnessKeyPathPay2tr(completeSig))
            signedTx.correctlySpends(mapOf(outpoint to prevTxOut), ScriptFlags.MANDATORY_SCRIPT_VERIFY_FLAGS)
        }
    }
}
