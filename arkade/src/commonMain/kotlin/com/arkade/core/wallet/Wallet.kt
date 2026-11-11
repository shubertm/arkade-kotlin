package com.arkade.core.wallet

import androidx.room.RoomDatabase
import com.arkade.core.ArkAddress
import com.arkade.core.ArkServerInfo
import com.arkade.core.bitcoin.Network
import com.arkade.core.contracts.ArkContract
import com.arkade.core.contracts.ContractState
import com.arkade.core.encodePubKeyByNetwork
import com.arkade.core.intents.ArkIntent
import com.arkade.core.intents.IntentState
import com.arkade.core.vtxos.Vtxo
import com.arkade.core.wallet.signer.WalletSignerManager
import com.arkade.di.ArkadeDI
import com.arkade.repositories.wallet.WalletRepo
import com.arkade.storage.db.Database
import com.arkade.storage.db.entities.WalletEntity
import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf

interface Wallet : WalletSignerManager {
    val id: String
    val secret: String
    val destination: String?
    val type: Type
    val accountDescriptor: String
    val lastUsedIndex: Int
    val network: Network
    val repo: WalletRepo

    /**
     * Persist the wallet's current state to its configured repository.
     *
     * This operation ensures the wallet's properties (id, secret, destination, type,
     * accountDescriptor, lastUsedIndex) are stored or updated in persistent storage.
     */
    suspend fun save()

    /**
     * Delete this wallet from persistent storage.
     *
     * Performs the repository-level removal so the wallet is no longer stored.
     */
    suspend fun delete()

    /**
     * Update the persisted wallet record to match the wallet's current state.
     *
     * Persists the wallet's properties (for example: `secret`, `destination`, `type`,
     * `accountDescriptor`, and `lastUsedIndex`) to the configured repository.
     */
    suspend fun update()

    /**
     * Set the wallet's last used address index and persist the change.
     *
     * @param index The new last-used address index (must be greater than or equal to 0).
     */
    suspend fun updateLastUsedIndex(index: Int): Boolean

    /**
     * Converts this wallet into a Room persistence entity.
     *
     * @return A `WalletEntity` containing this wallet's `id`, `secret`, optional `destination`,
     * `type`, `accountDescriptor`, `lastUsedIndex`, and `network`.
     */
    fun toRoomEntity(): WalletEntity =
        WalletEntity(
            id,
            secret,
            destination,
            type,
            fingerprint(),
            accountDescriptor,
            lastUsedIndex,
            network,
        )

    /**
     * Retrieves the wallet's fingerprint.
     *
     * @return The wallet's fingerprint if it exists (`type` is `HD`), `null` otherwise.
     */
    fun fingerprint(): String? {
        return if (type == Type.HD) {
            val startIndex = accountDescriptor.indexOf('[')
            if (startIndex == -1) return null
            val endIndex = accountDescriptor.indexOf('/', startIndex + 1)
            if (endIndex == -1) return null
            val fingerprint = accountDescriptor.substring(startIndex + 1, endIndex)
            require(fingerprint.length == 8) { "Invalid fingerprint length: expected 8 but is ${fingerprint.length}" }
            fingerprint
        } else {
            null
        }
    }

    suspend fun saveVtxo(vtxo: Vtxo.Data)

    suspend fun getVtxos(
        outpoints: Array<OutPoint>? = null,
        includeSpent: Boolean? = null,
    ): List<Vtxo.Data>

    suspend fun deleteVtxos()

    /**
     * Persists an [ArkContract] for this wallet.
     *
     * @param contract the contract to persist.
     * @param state the [ContractState] to associate with this contract.
     * @param network the Bitcoin network used to derive the contract's `scriptPubKey`.
     */
    suspend fun saveContract(
        contract: ArkContract,
        state: ContractState,
        network: Network,
    )

    /**
     * Retrieves all [ArkContract] instances stored for this wallet.
     *
     * @return a list of all contracts associated with this wallet's identifier.
     */
    suspend fun getContracts(
        scripts: Array<String>? = null,
        contractTypes: Array<String>? = null,
        state: ContractState? = null,
    ): List<ArkContract>

    /**
     * Deletes all [ArkContract] instances stored for this wallet.
     */
    suspend fun deleteContracts()

    suspend fun saveIntent(intent: ArkIntent)

    suspend fun getIntents(): List<ArkIntent>

    suspend fun getIntents(states: Array<IntentState>): List<ArkIntent>

    suspend fun deleteIntents()

    enum class Type {
        HD,
        SINGLE_KEY,
        ;

        companion object {
            /**
             * Classifies a secret as [SINGLE_KEY] or [HD] based on its encoding.
             *
             * @param secret Either an nsec-encoded private key (prefix "nsec") or an HD
             * mnemonic phrase.
             * @return [SINGLE_KEY] if `secret` starts with the nsec HRP, [HD] otherwise.
             */
            fun fromSecret(secret: String) =
                when {
                    secret.startsWith(NSEC_HRP) -> SINGLE_KEY
                    else -> HD
                }
        }
    }

    companion object {
        private const val NSEC_HRP = "nsec"

        /**
         * Derives the BIP-86 Taproot account key path and coin type for the given network.
         *
         * @param network The [Network] to derive the coin type for; mainnet uses coin type
         * 0, all other networks use coin type 1.
         * @return A pair of the account [KeyPath] (`m/86'/<coinType>'/0'`) and its coin type.
         */
        fun getAccountKeyPath(network: Network): Pair<KeyPath, Int> {
            val coinType =
                when (network) {
                    Network.MAINNET -> 0
                    else -> 1
                }
            return KeyPath("m/86'/$coinType'/0'") to coinType
        }

        /**
         * Create a Wallet from a secret (mnemonic phrase or an nsec-encoded private key) and an
         * optional destination tied to the provided server information.
         *
         * @param secret Either an HD mnemonic phrase or an nsec-encoded private key
         * (prefix "nsec"); when `secret` starts with `nsec`, a single-key wallet is created,
         * otherwise an HD wallet is created.
         * @param destination Optional [ArkAddress] for the wallet; when provided, the destination
         * is validated against `serverInfo`.
         * @param serverInfo Server information used to validate the destination and to derive
         * network-specific values for HD wallet creation.
         * @param dbBuilder Database builder passed to the repository for
         * initialization.
         * @return The created [Wallet] instance.
         */
        suspend fun create(
            secret: String,
            destination: String? = null,
            serverInfo: ArkServerInfo,
            dbBuilder: RoomDatabase.Builder<Database>,
        ): Wallet =
            withContext(Dispatchers.IO) {
                if (destination != null) {
                    validateDestination(destination, serverInfo)
                }

                val repo: WalletRepo = ArkadeDI.arkadeKoin.get { parametersOf(dbBuilder) }

                when (Type.fromSecret(secret)) {
                    Type.SINGLE_KEY -> createNSecWallet(secret, destination, repo, serverInfo.network)
                    Type.HD -> createHDWallet(secret, destination, serverInfo, repo)
                }
            }

        /**
         * Load a wallet by its identifier from persistent storage.
         *
         * @param id The wallet identifier to look up.
         * @param dbBuilder Database builder instance used for repository initialization
         * (primarily for tests).
         * @return The wallet with the given `id`, or `null` if no matching wallet is found.
         */
        suspend fun loadById(
            id: String,
            dbBuilder: RoomDatabase.Builder<Database>,
        ): Wallet? =
            withContext(Dispatchers.IO) {
                val repo: WalletRepo = ArkadeDI.arkadeKoin.get { parametersOf(dbBuilder) }
                repo.loadWalletById(id)
            }

        /**
         * Load a wallet by its fingerprint from persistent storage.
         *
         * @param fingerprint The wallet fingerprint to look up.
         * @param dbBuilder Database builder instance used for repository initialization
         * (primarily for tests).
         * @return The wallet with the given `fingerprint`, or `null` if no matching wallet is found.
         */
        suspend fun loadByFingerprint(
            fingerprint: String,
            dbBuilder: RoomDatabase.Builder<Database>,
        ): Wallet? =
            withContext(Dispatchers.IO) {
                val repo: WalletRepo = ArkadeDI.arkadeKoin.get { parametersOf(dbBuilder) }
                repo.loadWalletByFingerprint(fingerprint)
            }

        /**
         * Builds a Taproot output descriptor from an nsec-encoded private key.
         *
         * @param nsec The nsec (Bech32) encoded private key string.
         * @return A Taproot output descriptor in the form `tr(<xOnlyPublicKeyHex>)`.
         */
        fun getOutputDescriptorFromNSec(nsec: String): String {
            val privateKey = getPrivateKeyFromNSec(nsec)
            return "tr(${privateKey.publicKey().xOnly().value.toHex()})"
        }

        /**
         * Derives a master key from the provided mnemonic phrase.
         *
         * @param mnemonics is the mnemonic phrase to use for key derivation.
         * @return A pair containing the derived master key and its fingerprint.
         */
        internal fun masterKeyFromSecret(mnemonics: String): Pair<DeterministicWallet.ExtendedPrivateKey, String> {
            val seed = MnemonicCode.toSeed(mnemonics, "")
            val masterKey = DeterministicWallet.generate(seed)
            val fingerprint = masterKey.extendedPublicKey.keyFingerprint()
            return masterKey to fingerprint
        }

        /**
         * Extracts the first 32 bits of RIPEMD160 of SHA256 of a serialized extended public key
         *
         * @return A 4 byte hex string fingerprint
         */
        fun DeterministicWallet.ExtendedPublicKey.keyFingerprint(): String =
            Crypto
                .hash160(publickeybytes)
                .take(4)
                .toByteArray()
                .toHexString()

        /**
         * Creates a single-key wallet backed by the provided nsec-encoded private key.
         *
         * @param nsec The nsec-encoded private key string.
         * @param destination Optional destination address associated with the wallet.
         * @param repo Repository instance used by the returned wallet for persistence.
         * @param network The [Network] the wallet is created for.
         * @return A [Wallet] initialized with a Taproot output descriptor derived from `nsec`,
         * [Type.SINGLE_KEY], and `lastUsedIndex` set to 0.
         */
        private fun createNSecWallet(
            nsec: String,
            destination: String?,
            repo: WalletRepo,
            network: Network,
        ): Wallet {
            val outputDescriptor = getOutputDescriptorFromNSec(nsec)
            return WalletImpl(
                repo,
                outputDescriptor,
                nsec,
                destination,
                Type.SINGLE_KEY,
                outputDescriptor,
                0,
                network,
            )
        }

        /**
         * Creates an HD wallet from the provided mnemonic phrase and server information.
         *
         * Derives the Taproot account descriptor for the wallet using the server's network
         * and returns a [Wallet] configured as an HD wallet with `lastUsedIndex` set to 0.
         *
         * @param mnemonics The mnemonic phrase to validate and use as the wallet seed.
         * @param destination Optional destination address associated with the wallet.
         * @param serverInfo Server network and signing information used to choose coin type
         * and key encoding.
         * @param repo Repository used by the returned Wallet for persistence.
         * @return A [Wallet] instance of type `HD` with a derived Taproot account descriptor.
         */
        private fun createHDWallet(
            mnemonics: String,
            destination: String?,
            serverInfo: ArkServerInfo,
            repo: WalletRepo,
        ): Wallet {
            runCatching {
                MnemonicCode.validate(mnemonics)
            }.onFailure { throw it }

            val (masterKey, fingerprint) = masterKeyFromSecret(mnemonics)
            val network = serverInfo.network
            val (accountKeyPath, coinType) = getAccountKeyPath(network)
            val accountPrivateKey = masterKey.derivePrivateKey(accountKeyPath)
            val accountPublicKey = encodePubKeyByNetwork(accountPrivateKey.extendedPublicKey, serverInfo.network)
            require(fingerprint.length == 8) { "Invalid fingerprint length: expected 8 but is ${fingerprint.length}" }
            val accountDescriptor = "tr([$fingerprint/86'/$coinType'/0']$accountPublicKey/0/*)"

            return WalletImpl(
                repo,
                accountDescriptor,
                mnemonics,
                destination,
                Type.HD,
                accountDescriptor,
                0,
                network,
            )
        }

        /**
         * Validates that the provided Ark address targets the given server by comparing server
         * public keys.
         *
         * @param address Bech32-encoded [ArkAddress] whose embedded server public key will be
         * checked.
         * @param serverInfo Server information whose `signerPubKey` must match the address's
         * server public key.
         * @throws IllegalArgumentException if the address's server public key does not match
         * `serverInfo.signerPubKey`.
         */
        private fun validateDestination(
            address: String,
            serverInfo: ArkServerInfo,
        ) {
            val arkAddress = ArkAddress.decode(address)
            if (!serverInfo.signerPubKey.value
                    .toByteArray()
                    .contentEquals(arkAddress.serverPubKey)
            ) {
                throw IllegalArgumentException("Invalid destination server key")
            }
        }

        /**
         * Decodes a Bech32 `nsec` string and returns the corresponding private key.
         *
         * @param nsec The Bech32-encoded secret (expected HRP "nsec").
         * @return The private key represented by the decoded nsec payload.
         * @throws IllegalArgumentException If the HRP is not "nsec" or the decoded payload
         * is not 32 bytes.
         */
        internal fun getPrivateKeyFromNSec(nsec: String): PrivateKey {
            val (hrp, bytes, _) = Bech32.decodeBytes(nsec)
            require(hrp == NSEC_HRP) { "Invalid nsec HRP: $hrp" }
            require(bytes.size == 32) { "Invalid nsec payload size: ${bytes.size}" }
            return PrivateKey.fromHex(bytes.toHexString())
        }
    }
}
