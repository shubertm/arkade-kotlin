package com.arkade.core.assets

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput

/**
 * A group of asset [inputs] and [outputs] within a transaction's asset [Packet], either issuing
 * a new asset or transferring an existing one.
 *
 * A group with a `null` [assetId] is an issuance group: it creates a new asset (identified by
 * this group's own index within the packet), must have no [inputs], and may optionally specify a
 * [controlAsset] used to authorize future issuances/transfers of the asset. A group with a
 * non-null [assetId] transfers an existing asset and must not specify a [controlAsset].
 *
 * @property assetId The id of the asset being transferred, or `null` if this group issues a new
 * asset.
 * @property controlAsset A reference to the asset controlling this issuance; only valid when
 * [isIssuance] is `true`.
 * @property inputs The asset amounts consumed by this group; must be empty for issuances.
 * @property outputs The asset amounts produced by this group.
 * @property metadata Optional auxiliary key/value data attached to this group.
 */
class AssetGroup(
    val assetId: AssetId?,
    val controlAsset: AssetRef?,
    val inputs: List<AssetInput>,
    val outputs: List<AssetOutput>,
    val metadata: List<AssetMetadata>?,
) {
    /** Whether this group issues a new asset, i.e. has no [assetId]. */
    val isIssuance: Boolean
        get() = assetId == null

    /**
     * Validates this group's fields and their relationships.
     *
     * @throws IllegalArgumentException if both [inputs] and [outputs] are empty; if this is an
     * issuance group ([isIssuance]) with non-empty [inputs]; if this is a transfer group
     * (non-issuance) with a non-null [controlAsset]; if [inputs] contains more than one distinct
     * [AssetInput.type]; or if [inputs]/[outputs] contain duplicate [AssetInput.vin]/
     * [AssetOutput.vout] values, respectively.
     */
    fun validate() {
        require(inputs.isNotEmpty() || outputs.isNotEmpty()) { "Empty asset group" }
        if (isIssuance) {
            require(inputs.isEmpty()) { "Issuance asset group must have no inputs" }
        } else {
            require(controlAsset == null) { "Only issuance can have a control asset" }
        }

        if (inputs.size > 1) {
            val firstType = inputs[0].type
            val allSameType = inputs.all { input -> input.type == firstType }
            require(allSameType) { "Asset inputs must be of the same type" }

            val seenVins: HashSet<Int> = hashSetOf()
            inputs.forEach { input ->
                val isNotSeen = seenVins.add(input.vin)
                require(isNotSeen) { "Duplicate asset input vin: ${input.vin}" }
            }
        }

        if (outputs.size > 1) {
            val seenVouts: HashSet<Int> = hashSetOf()
            outputs.forEach { output ->
                val isNotSeen = seenVouts.add(output.vout)
                require(isNotSeen) { "Duplicate asset output vout: ${output.vout}" }
            }
        }
    }

    fun serialize(): ByteArray {
        val output = ByteArrayOutput()
        serializeTo(output)
        return output.toByteArray()
    }

    fun serializeTo(output: ByteArrayOutput) {
        validate()
        var presence = 0
        if (assetId != null) {
            presence = presence or MASK_ASSET_ID
        }
        if (controlAsset != null) {
            presence = presence or MASK_CONTROL_ASSET
        }
        if (!metadata.isNullOrEmpty()) {
            presence = presence or MASK_METADATA
        }
        output.write(presence)

        assetId?.serializeTo(output)

        controlAsset?.serializeTo(output)

        if (!metadata.isNullOrEmpty()) {
            serializeMetadata(output)
        }

        output.writeVarInt(inputs.size.toLong())
        inputs.forEach { input ->
            input.serializeTo(output)
        }

        output.writeVarInt(outputs.size.toLong())
        outputs.forEach { assetOutput ->
            assetOutput.serializeTo(output)
        }
    }

    fun toBatchLeafAssetGroup(intentTxId: ByteArray): AssetGroup {
        require(!isIssuance) { "Cannot create leaf asset group for issuance" }
        val leafInput = AssetInput.createIntent(intentTxId, 0, 0)
        val group =
            AssetGroup(
                assetId,
                controlAsset,
                listOf(leafInput),
                outputs,
                metadata,
            )
        group.validate()
        return group
    }

    override fun toString(): String = serialize().toHexString()

    private fun serializeMetadata(output: ByteArrayOutput) {
        output.writeVarInt(metadata?.size?.toLong()!!)
        metadata.forEach { metadata ->
            metadata.serializeTo(output)
        }
    }

    companion object {
        fun create(
            assetId: AssetId?,
            controlAsset: AssetRef?,
            inputs: List<AssetInput>,
            outputs: List<AssetOutput>,
            metadata: List<AssetMetadata>?,
        ): AssetGroup {
            val group =
                AssetGroup(
                    assetId,
                    controlAsset,
                    inputs,
                    outputs,
                    metadata,
                )
            group.validate()
            return group
        }

        /**
         * Parses an [AssetGroup] from [input]'s binary representation.
         *
         * The format starts with a presence bitmask byte ([MASK_ASSET_ID], [MASK_CONTROL_ASSET],
         * [MASK_METADATA]) indicating which optional fields follow, then those optional fields
         * in that order, then a var-int input count and that many [AssetInput]s, then a var-int
         * output count and that many [AssetOutput]s.
         *
         * @param input The buffer to read from.
         * @return The parsed and [validate]d [AssetGroup].
         * @throws IllegalArgumentException if any nested field fails to parse, or if the parsed
         * group fails [validate].
         */
        fun fromBytesInput(input: ByteArrayInput): AssetGroup {
            val presence = input.read()

            var assetId: AssetId? = null
            var controlAsset: AssetRef? = null
            var metadata: List<AssetMetadata>? = null

            if ((presence and MASK_ASSET_ID) != 0) {
                assetId = AssetId.fromBytesInput(input)
            }

            if ((presence and MASK_CONTROL_ASSET) != 0) {
                controlAsset = AssetRef.fromBytesInput(input)
            }

            if ((presence and MASK_METADATA) != 0) {
                metadata = deserializeMetadataList(input)
            }

            val inputCount = input.readVarIntToInt()

            val inputs: MutableList<AssetInput> = mutableListOf()
            for (i in 0 until inputCount) {
                inputs.add(AssetInput.fromBytesInput(input))
            }

            val outputCount = input.readVarIntToInt()

            val outputs: MutableList<AssetOutput> = mutableListOf()
            for (i in 0 until outputCount) {
                outputs.add(AssetOutput.fromBytesInput(input))
            }

            val group = AssetGroup(assetId, controlAsset, inputs, outputs, metadata)
            group.validate()
            return group
        }

        /** Parses a var-int-prefixed list of [AssetMetadata] entries from [input]. */
        private fun deserializeMetadataList(input: ByteArrayInput): List<AssetMetadata> {
            val count = input.readVarIntToInt()
            val metadata: MutableList<AssetMetadata> = mutableListOf()
            for (i in 0 until count) {
                metadata.add(AssetMetadata.fromBytesInput(input))
            }
            return metadata
        }
    }
}
