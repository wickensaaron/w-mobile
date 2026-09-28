package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.buildHomeContinueWatchingItems
import com.nuvio.app.features.home.buildHomeNextUpSeedCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileLiveTvProgressBoundaryTest {
    @Test
    fun `explicit live and archive identities require an owned mobile resolver`() {
        listOf("live", "LIVE", "livetv", "live-tv", "live_tv", " Live-TV ",
            "catchup", "catchup-partial", "live-tv-replay", "live-tv-catchup").forEach { type ->
            assertTrue(entry().copy(contentType = type).isUnsupportedMobileLiveTvProgress())
            assertTrue(entry().copy(parentMetaType = type).toContinueWatchingItem().isUnsupportedMobileLiveTvProgress())
        }
        listOf("live-tv", "live-tv-catchup").forEach { provider ->
            assertTrue(entry().copy(providerAddonId = provider).isUnsupportedMobileLiveTvProgress())
        }
        listOf("wcatchup:source:20180:1730000000", "wcatchup-partial:source:20180:1730000000").forEach { id ->
            // Some imported/cached rows were previously labelled series or movie.
            assertTrue(entry().copy(videoId = id).isUnsupportedMobileLiveTvProgress())
            assertTrue(entry().copy(parentMetaId = id).toContinueWatchingItem().isUnsupportedMobileLiveTvProgress())
        }
    }

    @Test
    fun `UUID and numeric addon movie identities are not guessed to be Live TV`() {
        listOf("f4c790ff-42ba-4f7c-bb2d-9be900efccfa:20180", "20180", "tt1234567").forEach { id ->
            val movie = entry().copy(parentMetaId = id, videoId = id)
            assertFalse(movie.isUnsupportedMobileLiveTvProgress())
            assertFalse(movie.toContinueWatchingItem().isUnsupportedMobileLiveTvProgress())
            assertTrue(movie.needsRemoteMetadataEnrichment())
        }
    }

    @Test
    fun `Home excludes unsupported imported rows and cached Next Up without deleting progress`() {
        val live = entry().copy(contentType = "live-tv", parentMetaType = "live-tv", title = "BBC Two")
        val replay = entry().copy(contentType = "live-tv-replay", parentMetaType = "live-tv-replay", progressKey = "replay-progress-key")
        val archive = entry().copy(videoId = "wcatchup:source:20180:1730000000", progressKey = "archive-progress-key")
        val movie = entry().copy(parentMetaId = "tt1234567", videoId = "tt1234567", title = "Movie", progressKey = "movie-progress-key")
        val storedHistory = listOf(live, replay, archive, movie)
        val payloadBeforeProjection = WatchProgressCodec.encodeEntries(storedHistory)
        val cachedArchive = archive.toContinueWatchingItem().copy(isNextUp = true)
        val cachedTvReplay = replay.toContinueWatchingItem().copy(isNextUp = true)

        val cards = buildHomeContinueWatchingItems(
            visibleEntries = storedHistory,
            nextUpItemsBySeries = mapOf(
                "archive-cache" to (500L to cachedArchive),
                "tv-replay-cache" to (500L to cachedTvReplay),
            ),
        )

        assertEquals(listOf("Movie"), cards.map { it.title })
        assertEquals(payloadBeforeProjection, WatchProgressCodec.encodeEntries(storedHistory))
        val restored = WatchProgressCodec.decodeEntries(payloadBeforeProjection)
        assertEquals(storedHistory.size, restored.size)
        assertEquals(storedHistory.map { it.videoId }.toSet(), restored.map { it.videoId }.toSet())
        assertEquals("stable-sync-progress-key", live.progressKey)
        assertEquals("wcatchup:source:20180:1730000000", archive.videoId)
    }

    @Test
    fun `mislabelled archive episodes never start generic Next Up resolution`() {
        val archive = entry().copy(
            contentType = "series",
            parentMetaType = "series",
            parentMetaId = "wcatchup-partial:source:20180:1730000000",
            seasonNumber = 1,
            episodeNumber = 2,
            isCompleted = true,
        )
        var consultedGenericSeedPolicy = false
        val candidates = buildHomeNextUpSeedCandidates(
            progressEntries = listOf(archive),
            watchedItems = emptyList(),
            providerOwnsCompletedHistory = false,
            preferFurthestEpisode = false,
            nowEpochMs = 1_000L,
            shouldUseProgressSeed = { _, _ ->
                consultedGenericSeedPolicy = true
                true
            },
        )
        assertTrue(candidates.isEmpty())
        assertFalse(consultedGenericSeedPolicy)
    }

    @Test
    fun `generic addon metadata cannot overwrite synced Live TV identity or artwork`() {
        val live = entry().copy(contentType = "live-tv", title = "BBC Two", logo = "channel-logo")
        val replay = entry().copy(contentType = "live-tv-replay")
        val archive = entry().copy(videoId = "wcatchup:source:20180:1730000000")
        val unrelatedMetadata = MetaDetails(id = "addon-id", type = "movie", name = "Wrong addon title")

        listOf(live, replay, archive).forEach { progress ->
            assertFalse(progress.needsRemoteMetadataEnrichment())
            assertEquals(progress, enrichWatchProgressEntry(progress, unrelatedMetadata))
        }
    }

    private fun entry(): WatchProgressEntry = WatchProgressEntry(
        contentType = "movie",
        parentMetaId = "ordinary-addon-id",
        parentMetaType = "movie",
        videoId = "ordinary-addon-video",
        title = "ordinary-addon-id",
        lastPositionMs = 25_000L,
        durationMs = 100_000L,
        lastUpdatedEpochMs = 500L,
        progressKey = "stable-sync-progress-key",
    )
}
