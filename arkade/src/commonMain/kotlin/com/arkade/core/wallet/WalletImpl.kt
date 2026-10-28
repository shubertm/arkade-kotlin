package com.arkade.core.wallet

import com.arkade.core.bitcoin.Network
import com.arkade.core.contracts.ArkContract
import com.arkade.core.contracts.ContractState
import com.arkade.core.intents.ArkIntent
import com.arkade.core.intents.IntentState
import com.arkade.core.vtxos.Vtxo
import com.arkade.core.wallet.addresses.AddressProvider
import com.arkade.core.wallet.addresses.HDAddressProvider
import com.arkade.core.wallet.addresses.SingleKeyAddressProvider
import com.arkade.core.wallet.signer.HDSigner
import com.arkade.core.wallet.signer.Signer
import com.arkade.core.wallet.signer.SingleKeySigner
import com.arkade.repositories.wallet.WalletRepo
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.psbt.Psbt
import kotlinx.coroutines.CancellationException

/**
 * Default [Wallet] implementation backed by a [WalletRepo] for persistence.
 *
 * Wires up the [signer] and [addressProvider] appropriate for this wallet's [type]: an
 * HD wallet uses [HDSigner]/[HDAddressProvider] keyed off [lastUsedIndex], while a
 * single-key wallet uses [SingleKeySigner]/[SingleKeyAddressProvider].
 */
class WalletImpl(
    override val repo: WalletRepo,
    override val id: String,
    override val secret: String,
    override val destination: String?,
    override val type: Wallet.Type,
    override val accountDescriptor: String,
    override var lastUsedIndex: Int,
    override val network: Network,
) : Wallet {
    /** The [Signer] used to fulfill [sign] and [signMessage], chosen based on [type]. */
    override val signer: Signer =
        when (type) {
            Wallet.Type.SINGLE_KEY -> SingleKeySigner.fromNSec(secret)
            Wallet.Type.HD -> HDSigner.fromMnemonic(secret, network)
        }

    /**
     * The [AddressProvider] used to derive and recognize this wallet's descriptors, chosen
     * based on [type]. For HD wallets, it reads and updates [lastUsedIndex] via [updateLastUsedIndex].
     */
    private val addressProvider: AddressProvider =
        when (type) {
            Wallet.Type.SINGLE_KEY -> SingleKeyAddressProvider(accountDescriptor)
            Wallet.Type.HD ->
                HDAddressProvider(
                    accountDescriptor,
                    getLastUsedIndex = { lastUsedIndex },
                    updateLastUsedIndex = { updateLastUsedIndex(it) },
                )
        }

    /**
     * Persists this wallet to the configured repository.
     */
    override suspend fun save() = repo.saveWallet(this)

    /**
     * Delete this wallet from the repository.
     */
    override suspend fun delete() = repo.deleteWallet(id)

    /**
     * Persists the wallet's current state to the configured repository.
     */
    override suspend fun update() = repo.updateWallet(this)

    /**
     * Set the wallet's lastUsedIndex to `index` and attempt to persist the change.
     *
     * Validates that `index` is greater than or equal to the current `lastUsedIndex`, updates
     * the in-memory value, and calls `update()` to persist; if persistence fails, the
     * previous `lastUsedIndex` is restored, but only if `lastUsedIndex` still equals `index`
     * (i.e. no other concurrent call has already advanced it further), to avoid clobbering a
     * more recent successful update.
     *
     * @param index The new last-used index; must be greater than or equal to the current value.
     * @throws IllegalArgumentException if `index` is less than the current `lastUsedIndex`.
     */
    override suspend fun updateLastUsedIndex(index: Int): Boolean {
        require(index >= lastUsedIndex) { "Invalid last used index" }
        val oldLastUsedIndex = lastUsedIndex
        lastUsedIndex = index
        val result =
            runCatching {
                update()
            }.onFailure {
                if (it is CancellationException) throw it
                if (lastUsedIndex == index) lastUsedIndex = oldLastUsedIndex
            }
        return result.isSuccess
    }

    override suspend fun saveVtxo(vtxo: Vtxo.Data) = repo.saveVtxo(vtxo)

    override suspend fun getVtxos(
        outpoints: Array<OutPoint>?,
        includeSpent: Boolean?,
    ): List<Vtxo.Data> =
        repo.getVtxos(
            outpoints,
            includeSpent,
        )

    override suspend fun deleteVtxos() = repo.deleteVtxos()

    /**
     * Persists [contract] for this wallet by delegating to the repository with this wallet's [id].
     *
     * @param contract the contract to persist.
     * @param state the lifecycle state to associate with the contract.
     * @param network the Bitcoin network used to derive the contract's `scriptPubKey`.
     */
    override suspend fun saveContract(
        contract: ArkContract,
        state: ContractState,
        network: Network,
    ) {
        repo.saveContract(contract, state, id, network)
    }

    /**
     * Retrieves all contracts stored for this wallet.
     *
     * @return a list of [ArkContract] instances associated with this wallet's [id].
     */
    override suspend fun getContracts(
        scripts: Array<String>?,
        contractTypes: Array<String>?,
        state: ContractState?,
    ) = repo.getContracts(
        id,
        scripts,
        contractTypes,
        state,
    )

    /**
     * Deletes all contracts stored for this wallet.
     */
    override suspend fun deleteContracts() = repo.deleteContracts(id)

    override suspend fun saveIntent(intent: ArkIntent) {
        require(intent.walletId == id) { "Wallet should own intent" }
        repo.saveIntent(intent)
    }

    override suspend fun getIntents(): List<ArkIntent> = repo.getIntents(id)

    override suspend fun getIntents(states: Array<IntentState>): List<ArkIntent> = repo.getIntents(id, states)

    override suspend fun deleteIntents() = repo.deleteIntents(id)

    /**
     * Signs [psbt] by delegating to [signer].
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param psbt The PSBT to sign.
     * @param inputIndexes The indexes of the inputs to sign; if empty, all inputs are signed.
     * @return The PSBT with all requested inputs signed.
     */
    override suspend fun sign(
        descriptor: String,
        psbt: Psbt,
        inputIndexes: Array<Int>,
    ): Psbt = signer.sign(descriptor, psbt, inputIndexes)

    /**
     * Signs [psbt] by delegating to [signer].
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param psbt The PSBT to sign.
     * @param outpoints The outpoints of the inputs to sign; if empty, all inputs are signed.
     * @return The PSBT with all requested inputs signed.
     */
    override suspend fun sign(
        descriptor: String,
        psbt: Psbt,
        outpoints: Array<OutPoint>,
    ): Psbt = signer.sign(descriptor, psbt, outpoints)

    /**
     * Signs [message] by delegating to [signer].
     *
     * @param descriptor The output descriptor identifying which key to sign with.
     * @param message The message bytes to sign.
     * @return The resulting signature bytes.
     */
    override suspend fun signMessage(
        descriptor: String,
        message: ByteArray,
    ): ByteArray = signer.signMessage(descriptor, message)
}
