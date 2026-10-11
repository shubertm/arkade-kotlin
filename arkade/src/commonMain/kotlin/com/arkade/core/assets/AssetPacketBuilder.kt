package com.arkade.core.assets

import fr.acinq.bitcoin.TxOut

object AssetPacketBuilder {
    fun build(
        inputs: Iterable<Triple<String, Int, Long>>,
        outputs: Iterable<Triple<String, Int, Long>>,
        changeVOut: Int,
    ): TxOut =
        requireNotNull(buildPacket(inputs, outputs, changeVOut)) {
            "Failed to build packet"
        }.toTxOut()

    fun buildPacket(
        inputs: Iterable<Triple<String, Int, Long>>,
        outputs: Iterable<Triple<String, Int, Long>>,
        changeVOut: Int,
    ): Packet? {
        val inputsByAsset: MutableMap<String, List<Pair<Int, Long>>> = mutableMapOf()
        inputs.forEach { (assetId, vIn, amount) ->
            if (!inputsByAsset.containsKey(assetId)) {
                inputsByAsset[assetId] = emptyList()
            }
            inputsByAsset[assetId] = inputsByAsset[assetId]!!.plus(Pair(vIn, amount))
        }

        if (inputsByAsset.isEmpty()) return null

        val outputsByAsset: MutableMap<String, List<Pair<Int, Long>>> = mutableMapOf()
        outputs.forEach { (assetId, vOut, amount) ->
            if (!outputsByAsset.containsKey(assetId)) {
                outputsByAsset[assetId] = emptyList()
            }
            outputsByAsset[assetId] = outputsByAsset[assetId]!!.plus(Pair(vOut, amount))
        }

        val allAssetIds: HashSet<String> = inputsByAsset.keys.toHashSet()
        allAssetIds.union(outputsByAsset.keys)

        val groups =
            allAssetIds.sorted().map { assetIdStr ->
                val assetId = AssetId.fromString(assetIdStr)
                val groupInputs =
                    inputsByAsset.getValue(assetIdStr).map { (vIn, amount) ->
                        AssetInput.create(vIn, amount)
                    }
                val groupOutputs =
                    outputsByAsset
                        .getValue(assetIdStr)
                        .map { (vOut, amount) ->
                            AssetOutput.create(vOut, amount)
                        }.toMutableList()

                val totalInputs = groupInputs.sumOf { it.amount }

                val totalOutputs = groupOutputs.sumOf { it.amount }

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
