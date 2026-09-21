package com.arkade.core.contracts

import com.arkade.core.ArkAddress
import com.arkade.core.UNSPENDABLE_PUBKEY
import com.arkade.core.bitcoin.Network
import com.arkade.core.buildScriptTree
import com.arkade.core.coins.ArkCoin
import com.arkade.core.taproot.Parity
import com.arkade.core.taproot.TaprootSpendingInfo
import com.arkade.core.taproot.pubKeyFromTaprootDescriptor
import com.arkade.core.toXOnlyPubKey
import com.arkade.core.vtxos.Vtxo
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Script

/**
 * Abstract base class for all Arkade contracts.
 *
 * An [ArkContract] encapsulates the Taproot spending conditions for a contract on the Ark protocol.
 * Subclasses define the specific script leaves (via [getTapLeafScripts]) and any contract-specific
 * metadata (via [getAdditionalData]). The contract computes a Taproot output using an unspendable
 * internal key tweaked by the Merkle root of the script tree, following the standard key-path
 * disabled Taproot pattern.
 *
 * The serialized form of a contract is an `arkcontract=<type>` query string, optionally followed
 * by `&`-separated `key=value` pairs from [getAdditionalData]. This string can be parsed back into
 * a contract using [ArkContractParserImpl].
 *
 * @param serverDescriptor a Taproot descriptor (e.g. `tr(<xOnlyPubKeyHex>)`) for the Arkade server's public key.
 */
abstract class ArkContract(
    val walletId: String,
    protected val serverDescriptor: String?,
) {
    /**
     * The contract type identifier used for serialization and parser dispatch.
     * Must be unique across all registered contract types.
     */
    abstract val type: String

    abstract val defaultScope: ContractScope

    /**
     * Returns the serialized representation of this contract as an `arkcontract` query string.
     *
     * Format: `arkcontract=<type>` or `arkcontract=<type>&key1=value1&key2=value2&...`
     *
     * The `arkcontract` key is stripped from [getAdditionalData] to avoid duplication.
     */
    override fun toString(): String {
        val data = getAdditionalData().toMutableMap()
        data.remove("arkcontract")
        val dataString =
            data.entries.joinToString("&") {
                "${it.key}=${it.value}"
            }
        val arkContract = "arkcontract=$type"
        return if (dataString.isEmpty()) arkContract else "$arkContract&$dataString"
    }

    /**
     * Returns the Ark address associated with this contract on the given [network].
     *
     * The address is constructed from the server's public key and the Taproot output key
     * derived from [getTaprootSpendingInfo].
     *
     * @param network the Bitcoin network to derive the address for.
     * @return the [ArkAddress] for this contract.
     * @throws IllegalArgumentException if [network] or the server descriptor is missing.
     */
    open fun getArkAddress(network: Network? = null): ArkAddress {
        requireNotNull(network) { "Missing network" }
        val taprootSpendingInfo = getTaprootSpendingInfo()
        requireNotNull(serverDescriptor) { "Missing server descriptor" }
        return ArkAddress.create(
            network,
            pubKeyFromTaprootDescriptor(serverDescriptor).hexToByteArray(),
            taprootSpendingInfo.outputKey.value.toByteArray(),
        )
    }

    /**
     * Returns the P2TR `scriptPubKey` for this contract as a hex string.
     *
     * Delegates to [getArkAddress] by default.
     *
     * @param network the Bitcoin network.
     * @return the hex-encoded P2TR scriptPubKey.
     */
    open fun getScriptPubKey(network: Network): String = getArkAddress(network).toP2TRScriptPubkey().toHexString()

    /**
     * Computes the [TaprootSpendingInfo] for this contract.
     *
     * Builds the Taproot script tree from [getTapLeafScripts], computes the Merkle root,
     * and derives the output key by tweaking [UNSPENDABLE_PUBKEY] with that root. This disables
     * key-path spending and enforces that funds can only be spent via one of the script leaves.
     *
     * @return the [TaprootSpendingInfo] containing the output key, parity, Merkle root, and script tree.
     */
    protected fun getTaprootSpendingInfo(): TaprootSpendingInfo {
        val unSpendablePubKey = UNSPENDABLE_PUBKEY.toXOnlyPubKey()

        val leafScripts = getTapLeafScripts()

        val scriptTree = buildScriptTree(leafScripts)
        val merkleRoot = scriptTree.hash()

        val (outputKey, isOdd) = unSpendablePubKey.outputKey(merkleRoot)

        val taprootSpendingInfo =
            TaprootSpendingInfo(
                unSpendablePubKey,
                outputKey,
                Parity.fromBooleanIsOdd(isOdd),
                merkleRoot,
                scriptTree,
            )
        return taprootSpendingInfo
    }

    /**
     * Builds the control block that proves [script] is a leaf in this contract's Taproot tree.
     *
     * @throws IllegalArgumentException If [script] is not one of [getTapLeafScripts].
     */
    protected fun getControlBlock(script: ByteArray): ByteArray {
        val spendingInfo = getTaprootSpendingInfo()
        val spendingLeaf =
            spendingInfo.merkleScriptTree.findScript(ByteVector(script))
                ?: throw IllegalArgumentException("Invalid leaf script")

        return Script.ControlBlock
            .build(
                spendingInfo.internalKey,
                spendingInfo.merkleScriptTree,
                spendingLeaf,
            ).toByteArray()
    }

    /**
     * Returns the list of serialized Tapscript leaf scripts that form this contract's script tree.
     *
     * Each element is a raw serialized Bitcoin script. The order determines how the Taproot
     * Merkle tree is constructed by [buildScriptTree].
     *
     * @return a non-empty list of serialized tap leaf scripts.
     */
    abstract fun getTapLeafScripts(): List<ByteArray>

    /**
     * Returns the contract's additional data as a key/value map.
     *
     * This data is included in the serialized contract string and used for round-trip parsing.
     * It should contain all parameters necessary to reconstruct the contract from its string form.
     *
     * @return a map of contract-specific key/value pairs.
     */
    abstract fun getAdditionalData(): Map<String, String>

    abstract suspend fun toArkCoin(vtxo: Vtxo.Data): ArkCoin
}
