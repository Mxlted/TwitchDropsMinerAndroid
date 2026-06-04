package com.nathan.twitchdropsminer.android

import com.nathan.twitchdropsminer.android.data.backend.BackendUrlValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendUrlValidatorTest {
    @Test
    fun normalizesTrailingSlash() {
        val result = BackendUrlValidator.normalize("http://10.0.2.2:8080/")
        assertEquals("http://10.0.2.2:8080", result.getOrThrow())
    }

    @Test
    fun allowsHttpsBackendHosts() {
        val result = BackendUrlValidator.normalize("https://miner.example.com:8443/")
        assertEquals("https://miner.example.com:8443", result.getOrThrow())
    }

    @Test
    fun rejectsInvalidScheme() {
        val result = BackendUrlValidator.normalize("ftp://example.com")
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsEmbeddedCredentials() {
        val result = BackendUrlValidator.normalize("http://user:pass@example.com:8080")
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsCleartextNonLocalHosts() {
        val result = BackendUrlValidator.normalize("http://192.168.1.50:8080")
        assertTrue(result.isFailure)
    }
}
