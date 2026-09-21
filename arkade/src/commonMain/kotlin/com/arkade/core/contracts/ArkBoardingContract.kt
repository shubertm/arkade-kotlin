package com.arkade.core.contracts

import com.arkade.core.ArkAddress
import com.arkade.core.bitcoin.Address
import com.arkade.core.bitcoin.Network
import com.arkade.core.coins.ArkCoin
import com.arkade.core.csvSigScript
import com.arkade.core.multisigScript
import com.arkade.core.taproot.getTaprootScriptPubKey
import com.arkade.core.taproot.parseTaprootDescriptor
import com.arkade.core.taproot.pubKeyFromTaprootDescriptor
import com.arkade.core.toXOnlyPubKey
import com.arkade.core.vtxos.ScriptSpendingPath
import com.arkade.core.vtxos.Vtxo

/**
 * An Arkade boarding contract that allows a user to move on-chain Bitcoin funds into the Arkade protocol.
 *
 * The contract produces a Taproot address with two spending paths:
 * - **Collaborative exit**: a 2-of-2 multisig between the server and the user, allowing
 *   cooperative settlement.
 * - **Unilateral exit**: a CSV-timelocked script spendable solely by the user after
 *   [exitDelay] blocks, providing a trustless fallback if the server becomes unresponsive.
 *
 * Because boarding contracts settle directly on-chain, [getArkAddress] is not supported.
 * Use [getOnChainAddress] to obtain the native Bitcoin P2TR address for this contract.
 *
 * @param serverDescriptor a Taproot descriptor (e.g. `tr(<xOnlyPubKeyHex>)`) for the Ark server's public key.
 * @param userDescriptor a Taproot descriptor for the user's public key.
 * @param exitDelay the CSV lock time (in blocks) for the unilateral exit path.
 */
class ArkBoardingContract(
    walletId: String,
    serverDescriptor: String,
    private val userDescriptor: String,
    private val exitDelay: Long,
) : ArkContract(walletId, serverDescriptor) {
    override val type: String = TYPE

    override val defaultScope: ContractScope = ContractScope.ON_CHAIN

    /**
     * Returns the on-chain Bitcoin P2TR address for this boarding contract on the given [network].
     *
     * @param network the Bitcoin network to derive the address for.
     * @return the native Bitcoin [Address] corresponding to this contract's Taproot output.
     */
    fun getOnChainAddress(network: Network): Address {
        val taprootSpendingInfo = getTaprootSpendingInfo()
        val scriptPubKey = getTaprootScriptPubKey(taprootSpendingInfo.outputKey.value.toByteArray())
        return Address.fromScriptPubKey(
            scriptPubKey,
            network,
        )
    }

    /**
     * Not supported for boarding contracts. Boarding contracts use on-chain Bitcoin addresses.
     *
     * @throws UnsupportedOperationException always. Use [getOnChainAddress] instead.
     */
    override fun getArkAddress(network: Network?): ArkAddress =
        throw UnsupportedOperationException("Boarding contracts use on-chain Bitcoin addresses. Use getOnChainAddress(network) instead.")

    /**
     * Returns the P2TR `scriptPubKey` for this boarding contract as a hex string.
     *
     * Derived from the on-chain address via [getOnChainAddress].
     *
     * @param network the Bitcoin network.
     * @return the hex-encoded P2TR scriptPubKey.
     */
    override fun getScriptPubKey(network: Network): String = getOnChainAddress(network).toScriptPubKey().toHexString()

    /**
     * Returns the two Tapscript leaf scripts for this boarding contract.
     *
     * The first element is the collaborative exit multisig script (server + user).
     * The second element is the unilateral exit CSV script (user only, after [exitDelay] blocks).
     *
     * @return a list containing [collaborativeExitScript, unilateralExitScript].
     */
    override fun getTapLeafScripts(): List<ByteArray> {
        requireNotNull(serverDescriptor) { "Invalid server descriptor" }
        val serverPubKey = pubKeyFromTaprootDescriptor(serverDescriptor).toXOnlyPubKey()
        val userPubKey = pubKeyFromTaprootDescriptor(userDescriptor).toXOnlyPubKey()
        val collaborativeScript = multisigScript(serverPubKey, userPubKey)
        val unilateralScript = csvSigScript(exitDelay, userPubKey)
        return listOf(collaborativeScript, unilateralScript)
    }

    /**
     * Returns the additional data required to reconstruct this boarding contract.
     *
     * Keys: `server` (server Taproot descriptor), `user` (user Taproot descriptor),
     * `exit_delay` (CSV lock time as a string).
     *
     * @return a map with keys `server`, `user`, and `exit_delay`.
     */
    override fun getAdditionalData(): Map<String, String> {
        requireNotNull(serverDescriptor) { "Invalid server descriptor" }
        return mapOf(
            "server" to serverDescriptor,
            "user" to userDescriptor,
            "exit_delay" to exitDelay.toString(),
        )
    }

    /**
     * Converts [vtxo] to an [ArkCoin] that uses the user's descriptor and collaborative spending path.
     *
     * The VTXO's lifecycle flags and optional asset metadata are preserved.
     */
    override suspend fun toArkCoin(vtxo: Vtxo.Data): ArkCoin =
        ArkCoin(
            walletId,
            contract = this,
            createdAt = vtxo.createdAt,
            expiresAt = vtxo.expiresAt,
            expiresAtHeight = vtxo.expiresAtHeight,
            outpoint = vtxo.outpoint,
            txOut = vtxo.txOut,
            signerDescriptor = userDescriptor,
            spendingScriptPath = collaborativePath(),
            spendingConditionWitness = null,
            lockTime = null,
            sequence = null,
            isSwept = vtxo.isSwept,
            isUnrolled = vtxo.isUnrolled,
            assets = vtxo.assets,
        )

    private fun collaborativePath(): ScriptSpendingPath {
        val scripts = getTapLeafScripts()
        val collaborativeScript = scripts[0]
        val controlBlock = getControlBlock(collaborativeScript)
        return ScriptSpendingPath(
            collaborativeScript,
            controlBlock,
        )
    }

    private fun unilateralPath(): ScriptSpendingPath {
        val scripts = getTapLeafScripts()
        val unilateralScript = scripts[1]
        val controlBlock = getControlBlock(unilateralScript)
        return ScriptSpendingPath(
            unilateralScript,
            controlBlock,
        )
    }

    companion object {
        /** The contract type identifier for boarding contracts. */
        const val TYPE = "Boarding"

        /**
         * Parses an [ArkBoardingContract] from a key/value data map.
         *
         * Expected keys: `server` (server Taproot descriptor or compressed/x-only public key hex),
         * `user` (user Taproot descriptor or compressed/x-only public key hex), and optionally
         * `exit_delay` (defaults to `0` if absent). Both descriptors are normalized to the canonical
         * `tr(<xOnlyPubKeyHex>)` form before construction.
         *
         * @param data the key/value map produced by [ArkContractParser.getAdditionalData].
         * @return an [ArkBoardingContract] constructed from the provided data.
         * @throws IllegalArgumentException if `server` or `user` keys are missing or blank,
         * or if `exit_delay` is a negative number.
         */
        fun parse(
            data: Map<String, String>,
            walletId: String,
        ): ArkContract {
            val serverPubKeyDescriptor = data["server"]
            val userPubKeyDescriptor = data["user"]
            val exitDelay = data["exit_delay"]?.toLong()
            requireNotNull(serverPubKeyDescriptor) { "Invalid server public key" }
            requireNotNull(userPubKeyDescriptor) { "Invalid user public key" }
            if (exitDelay != null) {
                require(exitDelay >= 0) { "Invalid exit delay" }
            }
            require(serverPubKeyDescriptor.isNotBlank()) { "Invalid server public key" }
            require(userPubKeyDescriptor.isNotBlank()) { "Invalid user public key" }
            return ArkBoardingContract(
                walletId,
                parseTaprootDescriptor(serverPubKeyDescriptor),
                parseTaprootDescriptor(userPubKeyDescriptor),
                exitDelay ?: 0,
            )
        }
    }
}
