package com.nuvio.app.core.deeplink

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupabaseAuthDeepLinkTest {
    @Test
    fun acceptsOnlyRegisteredAuthenticationCallbacks() {
        assertTrue(isSupabaseAuthCallback("wmedia://auth/google?code=opaque"))
        assertTrue(isSupabaseAuthCallback("wmedia://auth/confirm#access_token=opaque"))
        assertFalse(isSupabaseAuthCallback("wmedia://auth/google/other?code=opaque"))
        assertFalse(isSupabaseAuthCallback("wmedia://other/google?code=opaque"))
        assertFalse(isSupabaseAuthCallback("nuvio://auth/trakt?code=opaque"))
    }
}
