package com.nuvio.app.features.library

import com.nuvio.app.features.livetv.LiveTvRecording
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingShowsTest {
    private fun item(id: String, title: String, status: String = "ready") = LiveTvRecording(id, title, "Channel", startMs = id.toLong(), endMs = 100, status = status, protectSport = false, afterMinutes = 0, errorCode = null)
    @Test fun groupsEpisodesAndHidesCancelledAndDeletedItems() {
        val shows = recordingShows(listOf(item("1", "Pilot").copy(seriesTitle = "Show"), item("2", "Episode two").copy(seriesTitle = "Show"), item("3", "Cancelled", "cancelled"), item("4", "Deleted", "deleted")))
        assertEquals(listOf("Show"), shows.map { it.title })
        assertEquals(listOf("2", "1"), shows.single().items.map { it.id })
    }
}
