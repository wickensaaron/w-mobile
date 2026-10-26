package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleSignInDiagnosticTest {
    @Test fun keepsUsefulCauseTypesWhileRemovingSignInSecrets() {
        val diagnostic = googleSignInDiagnostic(IllegalStateException("Cannot open https://example.invalid/oauth?token=private-url", IllegalArgumentException("password=private-password access_token=private-token bearer private-bearer")))
        assertTrue(diagnostic.contains("IllegalStateException"))
        assertTrue(diagnostic.contains("IllegalArgumentException"))
        listOf("private-url", "private-password", "private-token", "private-bearer").forEach { assertFalse(diagnostic.contains(it)) }
    }
}
