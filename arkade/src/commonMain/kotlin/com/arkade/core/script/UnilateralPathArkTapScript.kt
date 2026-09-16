package com.arkade.core.script

import fr.acinq.bitcoin.OP_CHECKSEQUENCEVERIFY
import fr.acinq.bitcoin.OP_CHECKSIG
import fr.acinq.bitcoin.OP_DROP
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OP_VERIFY
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptElt

class UnilateralPathArkTapScript(
    private val timeout: Long,
    private val ownersMultisig: NofNMultisigTapScript,
    private val condition: ArkTapScript? = null,
) : ArkTapScript {
    /**
     * Builds the optional condition, relative timeout, and owner signature checks for this path.
     *
     * A non-empty condition is followed by `OP_VERIFY` before the timeout is enforced with
     * `OP_CHECKSEQUENCEVERIFY`.
     */
    override fun buildScript(): ByteArray {
        val conditionScript = condition?.buildScript() ?: byteArrayOf()
        val conditionASM = Script.parse(conditionScript).toMutableList()
        if (conditionASM.isNotEmpty()) {
            conditionASM.add(OP_VERIFY)
        }

        val multisigASM = Script.parse(ownersMultisig.buildScript()).toMutableList()
        multisigASM.removeAt(multisigASM.lastIndex)
        multisigASM.add(OP_CHECKSIG)

        conditionASM.add(OP_PUSHDATA(Script.encodeNumber(timeout)))
        conditionASM.add(OP_CHECKSEQUENCEVERIFY)
        conditionASM.add(OP_DROP)
        conditionASM.addAll(multisigASM)
        return Script.write(conditionASM)
    }

    companion object {
        /**
         * Reconstructs a unilateral path from its serialized Tapscript.
         *
         * Any operations before `OP_VERIFY` become the optional condition. The timeout is decoded
         * from the value immediately preceding `OP_CHECKSEQUENCEVERIFY`, and the operations after
         * `OP_DROP` are parsed as the owner multisignature script.
         *
         * @throws IllegalArgumentException If required timeout or signature operations are absent,
         * or the owner multisignature script is invalid.
         */
        fun parse(script: ByteArray): UnilateralPathArkTapScript {
            val scriptASM = Script.parse(script)

            var conditionASM = listOf<ScriptElt>()
            if (scriptASM.contains(OP_VERIFY)) {
                conditionASM = scriptASM.subList(0, scriptASM.indexOf(OP_VERIFY))
            }

            require(scriptASM.contains(OP_CHECKSEQUENCEVERIFY) && scriptASM.contains(OP_DROP)) { "Invalid unilateral path script" }
            val sequencePush = scriptASM[scriptASM.indexOf(OP_CHECKSEQUENCEVERIFY) - 1] as OP_PUSHDATA
            val timeout = Script.decodeNumber(sequencePush.data, true, 5)

            require(scriptASM.contains(OP_CHECKSIG)) { "Invalid unilateral path script" }
            val multisigASM = scriptASM.subList(scriptASM.indexOf(OP_DROP) + 1, scriptASM.size)

            return UnilateralPathArkTapScript(
                timeout,
                NofNMultisigTapScript.parse(Script.write(multisigASM)),
                GenericTapScript(conditionASM),
            )
        }
    }
}
