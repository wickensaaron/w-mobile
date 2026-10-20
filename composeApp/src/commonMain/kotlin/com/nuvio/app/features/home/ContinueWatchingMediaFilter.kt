package com.nuvio.app.features.home

/** Keep guide and recording history out of Home without deleting synced progress. */
internal fun excludeLiveOrRecordingFromHome(type: String, contentId: String, videoId: String): Boolean =
    type.trim().lowercase() in setOf("live", "livetv", "live-tv", "live_tv", "catchup", "catchup-partial",
        "recording", "live-tv-recording", "live-tv-replay", "live-tv-catchup") ||
        listOf(contentId, videoId).any { id ->
            listOf("wcatchup:", "wcatchup-partial:", "wcore-recording:").any { id.startsWith(it, ignoreCase = true) }
        }
