package com.arkade.core.script

interface ArkTapScript {
    /** Returns this Tapscript encoded in Bitcoin's serialized script format. */
    fun buildScript(): ByteArray
}
