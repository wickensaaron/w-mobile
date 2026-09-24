package com.nuvio.app.features.streams

import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WCorePlaybackSourcesTest {
    private val origin = "https://core.example.com"

    @Test
    fun `protected Core streams and subtitles use only the Core access token`() {
        val streams = parseWCorePlaybackSources(
            """{
              "sources": [{
                "sourceId": "jf-42", "provider": "jellyfin", "sourceLabel": "Library copy",
                "playbackUrl": "/api/v1/playback/stream/short-lived",
                "headers": {"Authorization": "Bearer upstream-secret", "Referer": "https://elsewhere.example"},
                "subtitles": [{"uri": "/api/v1/playback/subtitles/short-lived", "language": "en"}]
              }]
            }""".trimIndent(),
            origin,
            "core-token",
        )

        assertEquals(1, streams.size)
        val stream = streams.single()
        assertEquals("wcore:jellyfin", stream.addonId)
        assertEquals("$origin/api/v1/playback/stream/short-lived", stream.url)
        assertEquals(mapOf("Authorization" to "Bearer core-token"), stream.behaviorHints.proxyHeaders?.request)
        assertEquals(
            mapOf("Authorization" to "Bearer core-token"),
            stream.externalSubtitles.single().headers,
        )
    }

    @Test
    fun `external sources never receive the Core bearer token`() {
        val stream = parseWCorePlaybackSources(
            """{"sources":[{"sourceId":"remote-1","provider":"stremio",
              "playbackUrl":"https://cdn.example.com/video.m3u8",
              "headers":{"Authorization":"Bearer forbidden","Referer":"https://ref.example"}}]}""",
            origin,
            "core-token",
        ).single()

        assertEquals("https://cdn.example.com/video.m3u8", stream.url)
        assertEquals(mapOf("Referer" to "https://ref.example"), stream.behaviorHints.proxyHeaders?.request)
        assertFalse(stream.behaviorHints.proxyHeaders?.request.orEmpty().containsKey("Authorization"))
    }

    @Test
    fun `invalid playback URLs and malformed fields are skipped`() {
        val streams = parseWCorePlaybackSources(
            """{"sources":[
              {"sourceId":"bad-1","playbackUrl":"javascript:alert(1)"},
              {"sourceId":"bad-2","playbackUrl":"https://user:password@cdn.example/video"},
              {"sourceId":"bad-3","playbackUrl":{"nested":"value"}},
              {"sourceId":"good","playbackUrl":"https://cdn.example/video"}
            ]}""",
            origin,
            "core-token",
        )

        assertEquals(listOf("good"), streams.mapNotNull { it.sourceName })
        assertTrue(parseWCorePlaybackSources("not json", origin, "core-token").isEmpty())
    }

    @Test
    fun `smart mode prefers Jellyfin and manual mode keeps the picker`() {
        val remote = StreamItem(
            addonName = "Other", addonId = "addon:other", url = "https://cdn.example/video",
        )
        val jellyfin = StreamItem(
            addonName = "W Core", addonId = "wcore:jellyfin", url = "$origin/api/v1/playback/stream/short-lived",
        )
        fun choose(mode: StreamAutoPlayMode, source: StreamAutoPlaySource) =
            StreamAutoPlaySelector.selectAutoPlayStream(
                streams = listOf(remote, jellyfin), mode = mode, regexPattern = "",
                source = source, installedAddonNames = setOf("Other"),
                selectedAddons = emptySet(), selectedPlugins = emptySet(),
            )

        assertEquals(jellyfin, choose(StreamAutoPlayMode.SMART, StreamAutoPlaySource.ALL_SOURCES))
        assertNull(choose(StreamAutoPlayMode.MANUAL, StreamAutoPlaySource.ALL_SOURCES))
        assertEquals(remote, choose(StreamAutoPlayMode.SMART, StreamAutoPlaySource.INSTALLED_ADDONS_ONLY))
        assertNull(choose(StreamAutoPlayMode.SMART, StreamAutoPlaySource.ENABLED_PLUGINS_ONLY))
    }

    @Test
    fun `protected Core streams cannot enter the persisted download queue`() {
        val result = DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt1234567",
            parentMetaId = "tt1234567",
            parentMetaType = "movie",
            title = "Example",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                addonName = "W Core",
                addonId = "wcore:jellyfin",
                url = "$origin/api/v1/playback/stream/short-lived",
                behaviorHints = StreamBehaviorHints(
                    proxyHeaders = StreamProxyHeaders(request = mapOf("Authorization" to "Bearer core-token")),
                ),
            ),
        )

        assertEquals(DownloadEnqueueResult.UnsupportedFormat, result)
    }
}
