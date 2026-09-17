package com.arkade.core.script

import fr.acinq.bitcoin.OP_VERIFY
import fr.acinq.bitcoin.Script

class VerifyTapScript : ArkTapScript {
    override fun buildScript(): ByteArray = Script.write(listOf(OP_VERIFY))
}
