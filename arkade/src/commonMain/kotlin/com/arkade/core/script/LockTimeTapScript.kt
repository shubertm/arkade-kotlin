package com.arkade.core.script

import fr.acinq.bitcoin.OP_CHECKLOCKTIMEVERIFY
import fr.acinq.bitcoin.OP_DROP
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.Script

class LockTimeTapScript(
    private val lockTime: Long,
) : ArkTapScript {
    /**
     * Builds a script that enforces [lockTime] with `OP_CHECKLOCKTIMEVERIFY` and drops it.
     *
     * @throws IllegalArgumentException If [lockTime] is not positive.
     */
    override fun buildScript(): ByteArray {
        require(lockTime > 0) { "Lock time must be positive" }
        val lockTimePush =
            if (lockTime in 1..16) {
                Script.fromSimpleValue(lockTime.toByte())
            } else {
                OP_PUSHDATA(Script.encodeNumber(lockTime))
            }
        val asm =
            listOf(
                lockTimePush,
                OP_CHECKLOCKTIMEVERIFY,
                OP_DROP,
            )
        return Script.write(asm)
    }
}
