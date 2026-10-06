package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class LiveTvRecordingRulesTest {
    private val channel = LiveTvChannel("xtream:202", "News", "https://example.invalid/202.ts", playlistId = "provider:xtream", guideId = "news.uk")
    private val programme = LiveTvProgramme("news.uk", "News", startEpochMs = 1_000, stopEpochMs = 2_000)
    private val recording = LiveTvRecording("00000000-0000-0000-0000-000000000001", "News", "News", startMs = 200_000, endMs = 600_000, status = "ready", protectSport = false, beforeMinutes = 2, afterMinutes = 5, errorCode = null)

    @Test fun numericStreamRequiresAnExactXtreamSourceAndGuide() {
        assertEquals("202", recordingStreamId(channel, true))
        assertFailsWith<LiveTvRecordingException> { recordingStreamId(channel, false) }
        assertFailsWith<LiveTvRecordingException> { recordingStreamId(channel.copy(id = "m3u:202"), true) }
        assertFailsWith<LiveTvRecordingException> { recordingStreamId(channel.copy(id = "xtream:backup"), true) }
        assertFailsWith<LiveTvRecordingException> { recordingStreamId(channel.copy(guideId = null), true) }
    }

    @Test fun importedChannelMustMatchItsSourcePrefix() {
        val imported = channel.copy(id = "source-a:202", playlistId = "source-a", accountScope = LiveTvAccountScope("https://example.invalid", "owner", 1))
        assertEquals("202", recordingStreamId(imported, true))
        assertFailsWith<LiveTvRecordingException> { recordingStreamId(imported.copy(playlistId = "source-b"), true) }
    }

    @Test fun programmeEndRejectsExpiredMissingOrOverlongGuide() {
        assertEquals(programme, recordNowProgramme(RecordNowMode.ProgrammeEnd, programme, 1_500))
        assertNull(recordNowProgramme(RecordNowMode.ThirtyMinutes, programme, 3_000))
        assertFailsWith<LiveTvRecordingException> { recordNowProgramme(RecordNowMode.ProgrammeEnd, programme, 2_000) }
        assertFailsWith<LiveTvRecordingException> { recordNowProgramme(RecordNowMode.ProgrammeEnd, null, 1_500) }
        assertFailsWith<LiveTvRecordingException> { recordNowProgramme(RecordNowMode.ProgrammeEnd, programme.copy(stopEpochMs = 1_500 + 21_600_001), 1_500) }
    }

    @Test fun startOffsetUsesActualCaptureStartBeforePaddingFallback() {
        assertEquals(120_000L, recordingProgrammeStartOffsetMs(recording))
        assertEquals(30_000L, recordingProgrammeStartOffsetMs(recording.copy(recordFromMs = 170_000)))
        assertNull(recordingProgrammeStartOffsetMs(recording.copy(recordFromMs = 250_000)))
        assertNull(recordingProgrammeStartOffsetMs(recording.copy(status = "recording")))
        assertNull(recordingProgrammeStartOffsetMs(recording.copy(recordFromMs = -4_000_000)))
    }

    @Test fun failureMessageDoesNotReflectUntrustedServerText() {
        assertEquals("Recording failed. Check W Core status before trying again.", recordingFailureMessage("secret provider URL"))
    }

    @Test fun serverRecordingsPreserveExactIdentityCaptureOffsetAndStatus() {
        val decoded = decodeLiveTvRecording(Json.parseToJsonElement("""{
            "id":"00000000-0000-0000-0000-000000000001", "streamId":"202",
            "channel":{"name":"News","guideId":"news.uk"},
            "programme":{"title":"News","startMs":200000,"endMs":600000,"seasonNumber":1,"episodeNumber":2},
            "recordFromMs":170000,"status":"ready","beforeMinutes":2,"afterMinutes":5,
            "sourceFallbackUsed":true,"requestedChannelName":"News HD"
        }""").jsonObject)!!
        assertEquals("202", decoded.streamId)
        assertEquals("news.uk", decoded.guideId)
        assertEquals("ready", decoded.status)
        assertEquals(true, decoded.sourceFallbackUsed)
        assertEquals("News HD", decoded.requestedChannelName)
        assertEquals(30_000L, recordingProgrammeStartOffsetMs(decoded))
    }

    @Test fun incompleteServerItemsAreNotPublished() {
        assertNull(decodeLiveTvRecording(Json.parseToJsonElement("""{"id":"x","programme":{"title":"x"},"channel":{"name":"x"}}""").jsonObject))
        assertNull(decodeLiveTvRecording(Json.parseToJsonElement("""{"id":"x"}""").jsonObject))
    }
}
