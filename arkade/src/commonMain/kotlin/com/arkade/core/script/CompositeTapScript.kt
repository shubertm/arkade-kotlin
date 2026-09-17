package com.arkade.core.script

import fr.acinq.bitcoin.Script

class CompositeTapScript(
    private val scripts: List<ByteArray>,
) : ArkTapScript {
    override fun buildScript(): ByteArray {
        val asm =
            scripts.flatMap { script ->
                Script.parse(script)
            }
        return Script.write(asm)
    }
}
