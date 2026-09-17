package com.arkade.core.contracts

import com.arkade.core.coins.ArkCoin
import com.arkade.core.vtxos.Vtxo

/**
 * A flexible, general-purpose [ArkContract] that accepts arbitrary tap leaf scripts and metadata.
 *
 * [GenericArkContract] is useful when a specific contract type is not available but the
 * tap leaf scripts and optional additional data are already known. It is also used internally
 * by [ArkContractParserImpl] as the base for delegating to typed parsers such as
 * [ArkBoardingContract].
 *
 * The [type] is always `"generic"` and cannot be overridden.
 *
 * @param serverDescriptor a Taproot descriptor for the Arkade server's public key.
 * @param tapLeafScripts the list of serialized tap leaf scripts for this contract's script tree.
 * @param additionalData optional key/value metadata. Returns an empty map if `null`.
 */
class GenericArkContract(
    walletId: String,
    serverDescriptor: String,
    private val tapLeafScripts: List<ByteArray>,
    private val additionalData: Map<String, String>? = null,
) : ArkContract(walletId, serverDescriptor) {
    override val type: String = "generic"

    override val defaultScope: ContractScope = ContractScope.OFF_CHAIN

    override fun getTapLeafScripts(): List<ByteArray> = tapLeafScripts

    override fun getAdditionalData(): Map<String, String> = additionalData ?: emptyMap()

    override suspend fun toArkCoin(vtxo: Vtxo.Data): ArkCoin {
        TODO("Not yet implemented")
    }
}
