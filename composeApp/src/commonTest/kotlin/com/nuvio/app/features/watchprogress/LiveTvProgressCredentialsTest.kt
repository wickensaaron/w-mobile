package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LiveTvProgressCredentialsTest {
    private val providerUrl = "https://provider.invalid/live/private-user/private-password/123.ts"

    @Test
    fun `known live and archive providers never retain playback URLs`() {
        listOf("live-tv", " LIVE-TV ", "live-tv-catchup", "live-tv-replay", "livetv", "catchup-partial")
            .forEach { assertNull(scrubCoreSourceUrl(it, providerUrl)) }
        assertEquals(providerUrl, scrubCoreSourceUrl("ordinary-addon", providerUrl))
    }

    @Test
    fun `codec strips live URLs from entries deletes and parked account history`() {
        val row = progress("live", "channel-123")
        val deleted = progress("catchup", "replay-456")
        val parked = progress("movie", "wcatchup:provider:789")
        val payload = WatchProgressCodec.encodePayload(
            entries = listOf(row), lastSuccessfulPushEpochMs = 0,
            deltaCursorEventId = 0, deltaInitialized = false,
            pendingDeletes = listOf(deleted), syncIdentity = "account-a",
            otherIdentities = mapOf("account-b" to StoredWatchProgressPayload(entries = listOf(parked))),
        )
        assertFalse(payload.contains("private-user"))
        assertFalse(payload.contains("private-password"))
        val decoded = WatchProgressCodec.decodePayload(payload)
        assertEquals(row.copy(lastSourceUrl = null).resolvedProgressKey(), decoded.entries.single().resolvedProgressKey())
        assertEquals("A channel", decoded.entries.single().title)
        assertNull(decoded.entries.single().lastSourceUrl)
        assertNull(decoded.pendingDeletes.single().lastSourceUrl)
        assertNull(decoded.otherIdentities.getValue("account-b").entries.single().lastSourceUrl)
    }

    @Test
    fun `legacy history decode removes provider credentials without deleting progress`() {
        val safePayload = WatchProgressCodec.encodeEntries(listOf(progress("live", "channel-123")))
        val legacyPayload = safePayload.replace("\"lastSourceUrl\":null", "\"lastSourceUrl\":\"$providerUrl\"")
        val restored = WatchProgressCodec.decodeEntries(legacyPayload).single()
        assertNull(restored.lastSourceUrl)
        assertEquals("channel-123", restored.videoId)
        assertEquals(120_000L, restored.lastPositionMs)
    }

    private fun progress(type: String, id: String) = WatchProgressEntry(
        contentType = type, parentMetaType = type, parentMetaId = id, videoId = id,
        title = "A channel", lastPositionMs = 120_000L, durationMs = 600_000L,
        lastUpdatedEpochMs = 1L, lastSourceUrl = providerUrl,
    )
}
