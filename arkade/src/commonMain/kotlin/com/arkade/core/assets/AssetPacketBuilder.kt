package com.arkade.core.assets

import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxOut

object AssetPacketBuilder {
    /**
     * Builds a zero-satoshi `OP_RETURN` output containing the asset transfer packet from [buildPacket].
     * The tuple fields, change allocation, and validation rules are described by [buildPacket].
     *
     * @return `TxOut` or `null` if [inputs] is empty or asset's total output exceeds total input.
     * @throws IllegalArgumentException if packet construction or serialization fails validation.
     * @throws NoSuchElementException if an output asset has no corresponding input asset.
     */
    fun build(
        inputs: Iterable<Triple<String, Int, Long>>,
        outputs: Iterable<Triple<String, Int, Long>>,
        changeVOut: Int,
    ): TxOut? = buildPacket(inputs, outputs, changeVOut)?.toTxOut()

    /**
     * Builds a transfer packet with one group per asset, sorted by lowercase asset id.
     * Asset ids are matched without regard to case. Each asset's surplus is added to its output
     * at [changeVOut], or appended as a new asset output at that index if none exists.
     *
     * @param inputs Triples of hexadecimal [AssetId], transaction input index, and positive asset
     * amount. Ids use the 68-digit format accepted by [AssetId.fromString]; indices are in `0..65535`.
     * @param outputs Triples of hexadecimal [AssetId], transaction output index, and positive asset
     * amount, with the same id format and index range as [inputs]. May be empty to send all assets
     * to [changeVOut]. Each asset must also occur in [inputs].
     * @param changeVOut Transaction output index receiving surplus assets; must be in `0..65535`
     * when change is needed. It is unused when every asset's input and output totals match.
     * @return The packet, or `null` if [inputs] is empty (without inspecting [outputs] or [changeVOut]),
     * or output total exceeds input total
     * @throws IllegalArgumentException if an asset id is invalid, an amount is not positive, an
     * input or output index is out of range, or input indices or output indices repeat within an
     * asset group, if an asset's input or output total exceeds [Satoshi.MAX_MONEY], or a needed [changeVOut] is out of range.
     * @throws NoSuchElementException if [inputs] is nonempty and a valid output asset id has no
     * corresponding input asset.
     */
    fun buildPacket(
        inputs: Iterable<Triple<String, Int, Long>>,
        outputs: Iterable<Triple<String, Int, Long>>,
        changeVOut: Int,
    ): Packet? {
        val inputsByAsset: MutableMap<String, List<Pair<Int, Long>>> = mutableMapOf()
        inputs.forEach { (assetId, vIn, amount) ->
            val assetId = assetId.lowercase()
            if (!inputsByAsset.containsKey(assetId)) {
                inputsByAsset[assetId] = emptyList()
            }
            inputsByAsset[assetId] = inputsByAsset[assetId]!!.plus(Pair(vIn, amount))
        }

        if (inputsByAsset.isEmpty()) return null

        val outputsByAsset: MutableMap<String, List<Pair<Int, Long>>> = mutableMapOf()
        outputs.forEach { (assetId, vOut, amount) ->
            val assetId = assetId.lowercase()
            if (!outputsByAsset.containsKey(assetId)) {
                outputsByAsset[assetId] = emptyList()
            }
            outputsByAsset[assetId] = outputsByAsset[assetId]!!.plus(Pair(vOut, amount))
        }

        val allAssetIds: HashSet<String> =
            inputsByAsset.keys
                .union(outputsByAsset.keys)
                .toHashSet()

        val groups =
            allAssetIds.sorted().map { assetIdStr ->
                val assetId = AssetId.fromString(assetIdStr)
                val groupInputs =
                    inputsByAsset.getValue(assetIdStr).map { (vIn, amount) ->
                        AssetInput.create(vIn, amount)
                    }
                val groupOutputs =
                    outputsByAsset
                        .getOrElse(assetIdStr) { emptyList() }
                        .map { (vOut, amount) ->
                            AssetOutput.create(vOut, amount)
                        }.toMutableList()

                val maxMoneyAmount = Satoshi.MAX_MONEY.sat
                val totalInputs =
                    groupInputs.fold(0L) { total, input ->
                        require(input.amount <= maxMoneyAmount - total) {
                            "Input amount is too large: ${input.amount}"
                        }
                        total + input.amount
                    }

                val totalOutputs =
                    groupOutputs.fold(0L) { total, output ->
                        require(output.amount <= maxMoneyAmount - total) {
                            "Output amount is too large: ${output.amount}"
                        }
                        total + output.amount
                    }

                if (totalOutputs > totalInputs) {
                    return null
                }

                if (totalInputs > totalOutputs) {
                    val remaining = totalInputs - totalOutputs
                    val existingIndex =
                        groupOutputs.indexOfFirst { output ->
                            output.vOut == changeVOut
                        }

                    if (existingIndex >= 0) {
                        val existing = groupOutputs[existingIndex]
                        groupOutputs[existingIndex] = AssetOutput.create(existing.vOut, existing.amount + remaining)
                    } else {
                        groupOutputs.add(AssetOutput.create(changeVOut, remaining))
                    }
                }

                AssetGroup.create(assetId, null, groupInputs, groupOutputs, null)
            }
        return Packet(groups)
    }
}
