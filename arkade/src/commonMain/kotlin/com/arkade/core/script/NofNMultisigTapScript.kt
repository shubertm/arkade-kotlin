package com.arkade.core.script

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OP_CHECKSIG
import fr.acinq.bitcoin.OP_CHECKSIGVERIFY
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptElt
import fr.acinq.bitcoin.XonlyPublicKey

class NofNMultisigTapScript(
    private val owners: List<XonlyPublicKey>,
) : ArkTapScript {
    override fun buildScript(): ByteArray {
        val asm = mutableListOf<ScriptElt>()
        owners.forEach { owner ->
            asm.add(OP_PUSHDATA(owner))
            asm.add(OP_CHECKSIGVERIFY)
        }
        return Script.write(asm)
    }

    companion object {
        fun parse(script: ByteArray): NofNMultisigTapScript {
            val owners = mutableListOf<XonlyPublicKey>()
            val asm = Script.parse(script)
            require(asm.size >= 2 && asm.size % 2 == 0) { "Invalid multisig script" }

            for (i in asm.indices step 2) {
                val pushData = asm[i]
                val checkOp = asm[i + 1]
                require(pushData is OP_PUSHDATA && pushData.data.size() == 32) { "Invalid multisig script" }
                require(checkOp == OP_CHECKSIGVERIFY || (i == asm.lastIndex - 1 && checkOp == OP_CHECKSIG)) { "Invalid multisig script" }
                val owner = XonlyPublicKey(ByteVector32(pushData.data))
                owners.add(owner)
            }

            return NofNMultisigTapScript(owners.toList())
        }
    }
}
