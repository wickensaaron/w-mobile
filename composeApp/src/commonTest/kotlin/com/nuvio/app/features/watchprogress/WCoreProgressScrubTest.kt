package com.nuvio.app.features.watchprogress

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class WCoreProgressScrubTest {
    private fun entry(url: String, provider: String? = null) = WatchProgressEntry(
        "movie", "wm_movie123", "movie", "wm_movie123", "Film", lastPositionMs = 50_000,
        durationMs = 500_000, lastUpdatedEpochMs = 1, lastSourceUrl = url, providerAddonId = provider)

    @Test fun historicalCoreRoutesAndProviderAreScrubbedWithoutTouchingAddonLinks() {
        listOf("https://core.example/api/wcore/stream/ticket", "https://core.example/api/v1/playback/stream/ticket?token=secret",
            "https://core.example/api/v1/stream/ticket", "wcore-source://1").forEach {
            assertNull(scrubCoreSourceUrl(null, it))
        }
        assertNull(scrubCoreSourceUrl("wcore:jellyfin", "https://cdn.example/file.mp4"))
        val addon = "https://addon.example/movie.mp4?token=ordinary-addon-token"
        assertEquals(addon, scrubCoreSourceUrl("addon:example", addon))
        assertEquals("https://addon.example/api/v1/catalog/film", scrubCoreSourceUrl(null, "https://addon.example/api/v1/catalog/film"))
    }

    @Test fun decodeAndEncodeScrubAllAccountBucketsAndPendingDeletes() {
        val stale = entry("https://core.example/api/wcore/stream/secret")
        val bucket = StoredWatchProgressPayload(entries = listOf(stale), pendingDeletes = listOf(stale.copy(videoId = "wm_other123")))
        val stored = bucket.copy(otherIdentities = mapOf("other-account" to bucket))
        val decoded = WatchProgressCodec.decodePayload(Json.encodeToString(stored))
        assertNull(decoded.entries.single().lastSourceUrl)
        assertNull(decoded.otherIdentities.getValue("other-account").entries.single().lastSourceUrl)
        assertNull(decoded.otherIdentities.getValue("other-account").pendingDeletes.single().lastSourceUrl)
        val encoded = WatchProgressCodec.encodePayload(listOf(stale), 0, 0, false, pendingDeletes = listOf(stale), otherIdentities = stored.otherIdentities)
        assertFalse(encoded.contains("/api/wcore/stream/"))
        assertTrue(encoded.contains("Film"))
    }
}
