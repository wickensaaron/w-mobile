package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadSourceEligibilityTest {
    private fun source(url: String?, type: String? = null, addonId: String = "addon:example") = StreamItem(
        url = url,
        streamType = type,
        addonId = addonId,
        addonName = "Example",
    )

    @Test
    fun directHttpVideoFileIsAvailableForDownload() {
        assertTrue(source("https://example.invalid/video.mp4").isDirectFileDownloadSource)
    }

    @Test
    fun adaptiveAndNonFileSourcesDoNotEnterTheDownloadPicker() {
        assertFalse(source("https://example.invalid/live.m3u8?token=1").isDirectFileDownloadSource)
        assertFalse(source("https://example.invalid/list.m3u").isDirectFileDownloadSource)
        assertFalse(source("https://example.invalid/video", type = "HLS").isDirectFileDownloadSource)
        assertFalse(source("https://example.invalid/video", type = "application/dash+xml").isDirectFileDownloadSource)
        assertFalse(source("magnet:?xt=urn:btih:abc").isDirectFileDownloadSource)
        assertFalse(source("https://example.invalid/video.mp4", addonId = "wcore:source").isDirectFileDownloadSource)
        assertFalse(source(null).isDirectFileDownloadSource)
    }
}
