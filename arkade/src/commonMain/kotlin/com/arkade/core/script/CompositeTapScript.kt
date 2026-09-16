package com.arkade.core.script

import fr.acinq.bitcoin.Script

class CompositeTapScript(
    private val scripts: List<ByteArray>,
) : ArkTapScript {
    /** Returns the serialized script formed by concatenating the operations in [scripts]. */
    override fun buildScript(): ByteArray {
        val asm =
            scripts.flatMap { script ->
                Script.parse(script)
            }
        return Script.write(asm)
    }
}
