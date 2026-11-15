package com.arkade.core

import java.security.SecureRandom

actual fun secureRandom(): ByteArray {
    val entropy = ByteArray(32)
    SecureRandom().nextBytes(entropy)
    return entropy
}
