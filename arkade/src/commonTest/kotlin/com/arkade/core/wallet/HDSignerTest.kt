package com.arkade.core.wallet

import com.arkade.core.bitcoin.Network
import com.arkade.core.wallet.addresses.HDAddressProvider
import com.arkade.core.wallet.signer.HDSigner
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.SecretNonce
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
}
