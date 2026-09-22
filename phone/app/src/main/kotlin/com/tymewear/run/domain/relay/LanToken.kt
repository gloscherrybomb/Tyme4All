package com.tymewear.run.domain.relay

import java.security.SecureRandom
import java.util.Base64

object LanToken {
    /** 16 random bytes as unpadded base64url: 22 characters, safe in a URL query. */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
