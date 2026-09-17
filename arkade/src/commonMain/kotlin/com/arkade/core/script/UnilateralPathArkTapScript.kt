package com.arkade.core.script

import fr.acinq.bitcoin.OP_CHECKSEQUENCEVERIFY
import fr.acinq.bitcoin.OP_CHECKSIG
import fr.acinq.bitcoin.OP_DROP
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OP_VERIFY
import fr.acinq.bitcoin.Script

class UnilateralPathArkTapScript(
    private val timeout: Long,
    private val ownersMultisig: NofNMultisigTapScript,
    private val condition: ArkTapScript? = null,
) : ArkTapScript {
    override fun buildScript(): ByteArray {
        require(timeout >= 0 && (timeout and 0x0040FFFFL) == timeout) { "Invalid timeout, must not be negative" }

        val conditionScript = condition?.buildScript() ?: byteArrayOf()
        val conditionASM = Script.parse(conditionScript).toMutableList()
        if (conditionASM.isNotEmpty()) {
            conditionASM.add(OP_VERIFY)
        }

        conditionASM.add(OP_PUSHDATA(Script.encodeNumber(timeout)))
        conditionASM.add(OP_CHECKSEQUENCEVERIFY)
        conditionASM.add(OP_DROP)

        val multisigASM = Script.parse(ownersMultisig.buildScript()).toMutableList()
        multisigASM.removeAt(multisigASM.lastIndex)
        multisigASM.add(OP_CHECKSIG)

        conditionASM.addAll(multisigASM)
        return Script.write(conditionASM)
    }

    companion object {
        fun parse(script: ByteArray): UnilateralPathArkTapScript {
            val scriptASM = Script.parse(script)

            require(scriptASM.contains(OP_CHECKSEQUENCEVERIFY) && scriptASM.contains(OP_DROP)) { "Invalid unilateral path script" }

            val csvIndex = scriptASM.indexOf(OP_CHECKSEQUENCEVERIFY)
            val lastOpVerifyIndex = csvIndex - 2

            val conditionASM =
                if (lastOpVerifyIndex >= 0 && scriptASM[lastOpVerifyIndex] == OP_VERIFY) {
                    scriptASM.subList(0, lastOpVerifyIndex)
                } else {
                    emptyList()
                }

            val sequencePush = scriptASM[csvIndex - 1] as OP_PUSHDATA
            val timeout = Script.decodeNumber(sequencePush.data, true, 5)

            require(scriptASM.last() == OP_CHECKSIG && csvIndex + 1 < scriptASM.size && scriptASM[csvIndex + 1] == OP_DROP) {
                "Invalid unilateral path script"
            }
            val multisigASM = scriptASM.subList(csvIndex + 2, scriptASM.size)

            return UnilateralPathArkTapScript(
                timeout,
                NofNMultisigTapScript.parse(Script.write(multisigASM)),
                GenericTapScript(conditionASM),
            )
        }
    }
}
