package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoogleProviderSettingsTest {
    @Test
    fun readsPublicProviderAvailabilityWithoutCredentials() {
        assertEquals(true, googleProviderEnabled("""{"external":{"google":true}}"""))
        assertEquals(false, googleProviderEnabled("""{"external":{"google":false}}"""))
        assertNull(googleProviderEnabled("""{"external":{}}"""))
        assertNull(googleProviderEnabled("not json"))
    }
}
