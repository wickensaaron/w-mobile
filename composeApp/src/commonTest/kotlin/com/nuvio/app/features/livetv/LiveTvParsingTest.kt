package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class LiveTvParsingTest {
    @Test
    fun m3uPreservesGuideIdentityAndPlaybackUrl() {
        val channels = parseM3uPlaylist(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc.one" tvg-logo="https://example.test/logo.png" group-title="News",BBC One
            https://example.test/live/one.m3u8?token=opaque
            """.trimIndent(),
        )
        assertEquals(1, channels.size)
        assertEquals("bbc.one", channels.single().guideId)
        assertEquals("BBC One", channels.single().name)
        assertEquals("https://example.test/live/one.m3u8?token=opaque", channels.single().streamUrl)
    }

    @Test
    fun xmlTvMatchesProgrammeAndTimezoneWithoutLeakingOtherChannels() {
        val now = Instant.parse("2026-09-24T18:45:00Z").toEpochMilliseconds()
        val guide = parseXmlTvGuide(
            """
            <tv>
              <programme start="20260924193000 +0100" stop="20260924203000 +0100" channel="bbc.one">
                <title>News &amp; Weather</title><desc>Evening update</desc>
              </programme>
              <programme start="20260924180000 +0100" stop="20260924183000 +0100" channel="bbc.one">
                <title>Expired</title>
              </programme>
            </tv>
            """.trimIndent(),
            now,
        )
        assertEquals("News & Weather", guide["bbc.one"]?.single()?.title)
        assertEquals(now - 15 * 60 * 1000, guide["bbc.one"]?.single()?.startEpochMs)
        assertNull(guide["other.channel"])
    }
}
