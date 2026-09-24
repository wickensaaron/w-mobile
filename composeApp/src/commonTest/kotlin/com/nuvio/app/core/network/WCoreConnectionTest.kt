package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class WCoreConnectionTest {
    @Test
    fun coreOriginMustBePinnedHttpsOrigin() {
        assertEquals("https://core.wmedia.example", pinnedWCoreOrigin(" https://core.wmedia.example/ "))
        assertEquals("https://core.wmedia.example:8443", pinnedWCoreOrigin("https://core.wmedia.example:8443"))
        assertNull(pinnedWCoreOrigin("http://core.wmedia.example"))
        assertNull(pinnedWCoreOrigin("https://core.wmedia.example/api"))
        assertNull(pinnedWCoreOrigin("https://user:secret@core.wmedia.example"))
        assertNull(pinnedWCoreOrigin("https://core.wmedia.example?next=https://other.example"))
        assertNull(pinnedWCoreOrigin("https://core.wmedia.example:99999"))
        assertNull(pinnedWCoreOrigin("https://-core.wmedia.example"))
        assertNull(pinnedWCoreOrigin("https://core-.wmedia.example"))
        assertNull(pinnedWCoreOrigin("https://core.wmedia.example#fragment"))
        assertNull(pinnedWCoreOrigin("https://core.wmedia.example\\other"))
        assertNull(pinnedWCoreOrigin("https://core.wmedia.example.") )
        assertNull(pinnedWCoreOrigin(null))
    }

    @Test
    fun connectionNeedsBothConfiguredOriginAndAccount() {
        assertEquals(WCoreConnectionStatus.NotConfigured, idleWCoreStatus(null, null))
        assertEquals(WCoreConnectionStatus.NotConfigured, idleWCoreStatus(null, "user"))
        assertEquals(WCoreConnectionStatus.SignInRequired, idleWCoreStatus("https://core.example.com", null))
    }

    @Test
    fun coreSessionRequiresTokenAndFutureExpiry() {
        val now = Instant.parse("2026-09-24T12:00:00Z")
        assertEquals(
            "core-token",
            parseWCoreSession("""{"accessToken":"core-token","accessExpiresAt":"2026-09-24T12:15:00Z"}""", now)?.accessToken,
        )
        assertNull(parseWCoreSession("""{"accessToken":"","accessExpiresAt":"2026-09-24T12:15:00Z"}""", now))
        assertNull(parseWCoreSession("""{"accessToken":"core-token","accessExpiresAt":"2026-09-24T11:59:00Z"}""", now))
        assertNull(parseWCoreSession("""{"accessToken":"core-token"}""", now))
    }
}
