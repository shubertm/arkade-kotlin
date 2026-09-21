package com.arkade.core.bitcoin

/** Returns whether this lock value is interpreted as a block height. */
fun Long.isHeightLock() = this < 500_000_000

/** Returns whether this lock value is interpreted as a Unix timestamp. */
fun Long.isTimeLock() = !isHeightLock()
