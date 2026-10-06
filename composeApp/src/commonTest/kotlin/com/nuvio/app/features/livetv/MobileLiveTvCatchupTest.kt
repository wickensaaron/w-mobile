package com.nuvio.app.features.livetv

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class MobileLiveTvCatchupTest {
    @Test fun archiveAndRecordingUrlsNeverPersistAsResumeSources() {
        assertNull(com.nuvio.app.features.watchprogress.scrubCoreSourceUrl("live-tv-catchup", "https://provider.example/secret"))
        assertNull(com.nuvio.app.features.watchprogress.scrubCoreSourceUrl("live-tv-recording", "https://core.example/private-token"))
    }
    private val clock = MobileLiveTvArchiveClock
    private val zone = "Europe/London"
    private val now = clock.timestamp("2026-10-06T12:00:00Z", zone)!!
    private val source = MobileLiveTvArchiveSource("source-id", "source-id", "Provider", "https://provider.example/path", "user name", "private/pass")
    private val channel = LiveTvChannel("source-id:42", "Channel", "https://provider.example/live/42.ts", archive = LiveTvArchiveCapability(7))
    private val selection = LiveTvCatchupSelection(LiveTvCatchupOwner("owner", 1, "https://core.example", false), source, channel, channel.archive!!)
    private fun history(start: Long = now - 7_200_000, end: Long = now - 3_600_000, stream: String = "42", archive: Int = 1): LiveTvArchiveHistory {
        val row = buildJsonObject {
            put("stream_id", stream); put("start_timestamp", start / 1000); put("stop_timestamp", end / 1000)
            put("title", "Programme"); put("has_archive", archive)
        }
        val bytes = buildJsonObject { put("epg_listings", JsonArray(listOf(row))) }.toString().encodeToByteArray()
        return parseLiveTvArchiveHistory(bytes, selection, zone, "2026-10-06", clock, now)
    }

    @Test fun clockRejectsDstAmbiguityGapsAndInvalidDates() {
        assertNull(clock.timestamp("2026-10-25 01:30:00", zone))
        assertNull(clock.timestamp("2026-03-29 01:30:00", zone))
        assertNull(clock.timestamp("2026-02-30 12:00:00", zone))
        assertNotNull(clock.timestamp("2026-10-25T01:30:00+01:00", zone))
        assertEquals(25 * 3_600_000L, clock.dayBounds("2026-10-25", zone)!!.let { it.second - it.first })
        assertEquals("2026-10-06:13-00", clock.archiveStart(now, zone))
    }

    @Test fun retentionFinishedAndDurationAreRequired() {
        assertEquals(LiveTvArchiveStatus.UNKNOWN_RETENTION, liveTvArchiveEligibility(LiveTvArchiveCapability(null), now - 60000, now, now))
        assertEquals(LiveTvArchiveStatus.EXPIRED, liveTvArchiveEligibility(LiveTvArchiveCapability(1), now - 86_460_000, now - 86_400_000, now))
        assertEquals(LiveTvArchiveStatus.NOT_FINISHED, liveTvArchiveEligibility(channel.archive, now, now + 60000, now))
        assertEquals(LiveTvArchiveStatus.TOO_LONG, liveTvArchiveEligibility(channel.archive, now - 43_260_000, now, now))
    }

    @Test fun parserRejectsWrongSourceAndExplicitUnavailableRows() {
        assertTrue(history(stream = "99").programmes.isEmpty())
        assertTrue(history(archive = 0).programmes.isEmpty())
        assertEquals(1, history().programmes.size)
    }

    @Test fun partialAiringRequiresAdvertisementAndDoesNotCreateDurableHistory() {
        val current = history(start = now - 600_000, end = now + 600_000)
        val request = LiveTvCatchupRequest.fromHistory(current, current.airing!!, now, watchFromStart = true)!!
        assertTrue(request.partial)
        assertNull(liveTvCatchupHistoryId(request))
        assertNull(LiveTvCatchupRequest.fromHistory(current, current.airing!!, now))
    }

    @Test fun historyIdentityContainsNoCredentialsAndChangesWhenSourceChanges() {
        val loaded = history()
        val request = LiveTvCatchupRequest.fromHistory(loaded, loaded.programmes.single(), now)!!
        val id = liveTvCatchupHistoryId(request)!!
        assertNotNull(parseLiveTvCatchupHistoryId(id))
        assertFalse(id.contains("private"))
        assertFalse(id.contains("user name"))
        val changed = LiveTvCatchupSelection(selection.owner, source.copy(password = "different"), channel, channel.archive!!)
        assertNotEquals(liveTvCatchupFingerprint(selection), liveTvCatchupFingerprint(changed))
        assertNull(parseLiveTvCatchupHistoryId("wcatchup:garbage"))
    }

    @Test fun sourceUrlValidationRejectsCredentialAuthorityAndTraversal() {
        assertNull(liveTvXtreamArchiveBase(source.copy(serverUrl = "https://user:pass@provider.example")))
        assertNull(liveTvXtreamArchiveBase(source.copy(serverUrl = "https://provider.example/../private")))
        assertNull(liveTvXtreamArchiveBase(source.copy(serverUrl = "https://provider.example?redirect=x")))
        assertEquals("https://provider.example/path", liveTvXtreamArchiveBase(source))
    }

    @Test fun loaderRechecksOwnershipAfterNetworkAndPropagatesCancellation() = runTest {
        var owned = true
        val transport = object : LiveTvArchiveTransport {
            override suspend fun timezone(selection: LiveTvCatchupSelection): ByteArray {
                owned = false
                return """{"user_info":{"auth":1},"server_info":{"timezone":"UTC"}}""".encodeToByteArray()
            }
            override suspend fun history(selection: LiveTvCatchupSelection): ByteArray = throw CancellationException()
        }
        val loader = LiveTvCatchupLoader(clock, transport)
        assertEquals(LiveTvArchiveStatus.STALE_SELECTION, loader.timezone(selection) { owned }.status)
        assertFailsWith<CancellationException> { loader.history(selection, zone, "2026-10-06", now) { true } }
    }

    @Test fun oversizedHistoryIsRejectedBeforeParsing() = runTest {
        assertFailsWith<LiveTvArchiveSizeException> {
            readLiveTvArchiveBytes(ByteReadChannel(ByteArray(20)), null, 10)
        }
    }
}
