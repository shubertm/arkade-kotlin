package com.arkade.core.contracts

import com.arkade.core.ArkAddress
import com.arkade.core.bitcoin.Network
import com.arkade.core.coins.ArkCoin
import com.arkade.core.vtxos.Vtxo

class UnknownArkContract(
    walletId: String,
    serverDescriptor: String,
    private val address: ArkAddress,
) : ArkContract(walletId, serverDescriptor) {
    override val type: String = TYPE

    override val defaultScope: ContractScope = ContractScope.OFF_CHAIN

    /**
     * Always throws because an unknown contract does not expose scripts for signing.
     *
     * @throws UnsupportedOperationException Always.
     */
    override fun getTapLeafScripts(): List<ByteArray> = throw UnsupportedOperationException("Unknown contract cannot be used for signing")

    /** Returns the server descriptor and encoded address retained for this unknown contract. */
    override fun getAdditionalData(): Map<String, String> {
        requireNotNull(serverDescriptor) { "Missing server descriptor" }
        return mapOf(
            "server" to serverDescriptor,
            "address" to address.encode(),
        )
    }

    /**
     * This conversion is not implemented for unknown contracts.
     *
     * @throws NotImplementedError Always.
     */
    override suspend fun toArkCoin(vtxo: Vtxo.Data): ArkCoin {
        TODO("Not yet implemented")
    }

    /** Returns the stored address; [network] does not alter it. */
    override fun getArkAddress(network: Network?): ArkAddress = address

    /** Returns the stored address's P2TR script public key; [network] does not alter it. */
    override fun getScriptPubKey(network: Network): String = address.toP2TRScriptPubkey().toHexString()

    companion object {
        const val TYPE = "Unknown"

        /**
         * Reconstructs an unknown contract from its server descriptor and encoded Ark address.
         *
         * @throws IllegalArgumentException If either field is missing or the address cannot be
         * decoded.
         */
        fun parse(
            walletId: String,
            data: Map<String, String>,
        ): ArkContract {
            val serverDescriptor = data["server"]
            requireNotNull(serverDescriptor) { "Missing server descriptor" }
            val address = data["address"]
            requireNotNull(address) { "Missing address" }
            return UnknownArkContract(walletId, serverDescriptor, ArkAddress.decode(address))
        }
    }
}
