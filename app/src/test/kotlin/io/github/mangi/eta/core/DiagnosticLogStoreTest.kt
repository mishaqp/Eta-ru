package io.github.mangi.eta.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogStoreTest {
    @Test
    fun redactRemovesLabeledCredentialsAndCommonTokenPrefixes() {
        val value = DiagnosticLogStore.redact(
            "api_key=sk-123456789012345678 token: ghp_123456789012345678 " +
                "Authorization: Bearer secret-value password = pass123"
        )

        assertFalse(value.contains("sk-123456789012345678"))
        assertFalse(value.contains("ghp_123456789012345678"))
        assertFalse(value.contains("secret-value"))
        assertFalse(value.contains("pass123"))
        assertTrue(value.contains("[REDACTED]"))
    }

}
