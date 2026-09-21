package com.arkade.core.script

import fr.acinq.bitcoin.OP_CHECKSIG
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.XonlyPublicKey

class CollaborativePathArkTapScript(
    private val serverPubKey: XonlyPublicKey,
    private val condition: ArkTapScript? = null,
) : ArkTapScript {
    /**
     * Appends a server signature check to the optional [condition] script.
     *
     * @return The serialized Tapscript for the collaborative spending path.
     */
    override fun buildScript(): ByteArray {
        val conditionScript = condition?.buildScript() ?: byteArrayOf()
        val conditionASM = Script.parse(conditionScript).toMutableList()
        conditionASM.add(OP_PUSHDATA(serverPubKey))
        conditionASM.add(OP_CHECKSIG)
        return Script.write(conditionASM)
    }
}
