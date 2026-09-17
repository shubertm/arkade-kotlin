package com.arkade.core.bitcoin

fun Long.isHeightLock() = this < 500_000_000

fun Long.isTimeLock() = !isHeightLock()
