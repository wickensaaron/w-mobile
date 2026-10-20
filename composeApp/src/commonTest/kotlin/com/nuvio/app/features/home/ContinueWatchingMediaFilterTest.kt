package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinueWatchingMediaFilterTest {
    @Test fun importedAndLocalLiveHistoryNeverAppearsOnHome() {
        listOf("live", "live-tv", "catchup", "recording", "live-tv-recording", "live-tv-replay").forEach {
            assertTrue(excludeLiveOrRecordingFromHome(it, "channel:123", "video"), it)
        }
        assertTrue(excludeLiveOrRecordingFromHome("movie", "wcore-recording:123", "video"))
        assertTrue(excludeLiveOrRecordingFromHome("series", "tt123", "wcatchup:123"))
        assertFalse(excludeLiveOrRecordingFromHome("movie", "tt123", "tt123"))
        assertFalse(excludeLiveOrRecordingFromHome("series", "tt123", "tt123:1:2"))
    }
}
