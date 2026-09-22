package com.tymewear.run.domain.relay

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTokenTest {
    @Test fun `token is 22 url-safe characters and differs each time`() {
        val a = LanToken.generate(SecureRandom())
        val b = LanToken.generate(SecureRandom())
        assertEquals(22, a.length)
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]{22}")))
        assertNotEquals(a, b)
    }
}
