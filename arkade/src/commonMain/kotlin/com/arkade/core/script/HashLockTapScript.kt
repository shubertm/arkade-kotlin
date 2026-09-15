package com.arkade.core.script

import fr.acinq.bitcoin.OP_EQUAL
import fr.acinq.bitcoin.OP_HASH160
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OP_SHA256
import fr.acinq.bitcoin.Script

class HashLockTapScript(
    val hash: ByteArray,
    val hashLockType: HashLockType =
        if (hash.size == 20) HashLockType.HASH160 else HashLockType.SHA256,
) : ArkTapScript {
    override fun buildScript(): ByteArray {
        val asm =
            listOf(
                if (hashLockType == HashLockType.HASH160) OP_HASH160 else OP_SHA256,
                OP_PUSHDATA(hash),
                OP_EQUAL,
            )
        return Script.write(asm)
    }

    enum class HashLockType {
        HASH160,
        SHA256,
    }
}
