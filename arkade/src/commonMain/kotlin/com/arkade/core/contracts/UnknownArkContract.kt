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

    override fun getTapLeafScripts(): List<ByteArray> = throw UnsupportedOperationException("Unknown contract cannot be used for signing")

    override fun getAdditionalData(): Map<String, String> {
        requireNotNull(serverDescriptor) { "Missing server descriptor" }
        return mapOf(
            "server" to serverDescriptor,
            "address" to address.encode(),
        )
    }

    override suspend fun toArkCoin(vtxo: Vtxo.Data): ArkCoin {
        TODO("Not yet implemented")
    }

    override fun getArkAddress(network: Network?): ArkAddress = address

    override fun getScriptPubKey(network: Network): String = address.toP2TRScriptPubkey().toHexString()

    companion object {
        const val TYPE = "Unknown"

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
