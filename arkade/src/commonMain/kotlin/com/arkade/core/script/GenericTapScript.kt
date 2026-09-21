package com.arkade.core.script

import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptElt

class GenericTapScript(
    private val ops: List<ScriptElt>,
) : ArkTapScript {
    /** Returns [ops] in Bitcoin's serialized script format. */
    override fun buildScript(): ByteArray = Script.write(ops)
}
