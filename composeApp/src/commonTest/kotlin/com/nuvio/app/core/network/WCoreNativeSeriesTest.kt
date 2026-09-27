package com.nuvio.app.core.network

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.details.seriesPrimaryAction
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.player.nativeSeriesMatchesPlayback
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.streams.canonicalCorePlaybackBody
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WCoreNativeSeriesTest {
    private val seriesId = "wm_series123"
    private val cursor = "cursor_12345678901234567890"
    private val special = WCoreSeriesMember("wm_episode01", 0, 1, "Special")
    private val regular = WCoreSeriesMember("wm_episode02", 1, 2, "Second")
    private val session = WCoreLibraryScope("backend|account", 1, "https://core.example", "secret-token", 2)

    private fun page(items: List<WCoreSeriesMember>, offset: Int, total: Int, complete: Boolean, next: String? = null) = buildJsonObject {
        put("series", buildJsonObject { put("wMediaId", seriesId); put("jellyfinSeriesId", "jf-series"); put("title", "Local series") })
        put("items", buildJsonArray { items.forEach { member -> add(buildJsonObject {
            put("wMediaId", member.id); put("seriesWMediaId", seriesId); put("title", member.title)
            put("season", member.season); put("episode", member.episode)
            put("availability", buildJsonObject { put("local", true); put("playable", true) })
            put("playbackUrl", "never-retain-this-signed-ticket")
        }) } })
        put("pagination", buildJsonObject {
            put("snapshotId", "snapshot_123456789012"); put("offset", offset); put("totalItems", total)
            put("complete", complete); put("truncated", false); put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull)
        })
    }.toString()

    private fun change(body: String, block: MutableMap<String, JsonElement>.() -> Unit): String =
        JsonObject((Json.parseToJsonElement(body) as JsonObject).toMutableMap().apply(block)).toString()
    private fun pagination(body: String, key: String, value: JsonElement): String = change(body) {
        put("pagination", JsonObject((getValue("pagination") as JsonObject).toMutableMap().apply { put(key, value) }))
    }
    private fun item(body: String, key: String, value: JsonElement): String = change(body) {
        val rows = getValue("items") as JsonArray
        put("items", JsonArray(listOf(JsonObject((rows[0] as JsonObject).toMutableMap().apply { put(key, value) }))))
    }

    @Test fun completeContiguousPagesUseExactIdsAndSupportSeasonZero() = runBlocking {
        val requested = mutableListOf<String?>()
        val result = loadWCoreSeriesMembership(seriesId) { next ->
            requested += next
            if (next == null) page(listOf(special), 0, 2, false, cursor) else page(listOf(regular), 1, 2, true)
        }
        assertEquals(listOf(null, cursor), requested)
        assertEquals(listOf(special, regular), result.members)
        assertFalse(result.toString().contains("signed-ticket"))
        val meta = nativeCoreSeriesDetails(result, special.id)
        assertEquals(seriesId, meta.id)
        assertEquals("series", meta.type)
        assertEquals(special.id, meta.defaultVideoId)
        assertEquals(special.id, meta.seriesPrimaryAction(emptyList(), emptyList(), "2026-09-27")?.videoId)
        val next = PlayerNextEpisodeRules.resolveNextEpisode(meta.videos, 0, 1)!!
        assertEquals(regular.id, next.id)
        assertEquals(regular.id, buildPlaybackVideoId(seriesId, next.season, next.episode, next.id))
        assertTrue(canonicalCorePlaybackBody(next.id, null).contains(regular.id))
        assertTrue(nativeSeriesMatchesPlayback(meta, special.id, 0, 1))
        assertFalse(nativeSeriesMatchesPlayback(meta, "wm_different1", 0, 1))
        assertFalse(nativeSeriesMatchesPlayback(meta, special.id, 1, 1))
        assertFalse(meta.toLibraryItem(1).toString().contains("signed-ticket"))
    }

    @Test fun changedSnapshotSeriesTotalAndNonContiguousOffsetsRejectWholeAssembly() = runBlocking {
        val first = page(listOf(special), 0, 2, false, cursor)
        val last = page(listOf(regular), 1, 2, true)
        val invalid = listOf(
            pagination(last, "snapshotId", JsonPrimitive("different_snapshot12345")),
            pagination(last, "offset", JsonPrimitive(0)),
            pagination(last, "totalItems", JsonPrimitive(3)),
            change(last) { put("series", buildJsonObject { put("wMediaId", "wm_otherseries"); put("jellyfinSeriesId", "jf-series"); put("title", "Local series") }) },
            change(last) { put("series", buildJsonObject { put("wMediaId", seriesId); put("jellyfinSeriesId", "different-jf"); put("title", "Local series") }) },
        )
        invalid.forEach { bad ->
            assertFailsWith<IllegalStateException> { loadWCoreSeriesMembership(seriesId) { if (it == null) first else bad } }
        }
    }

    @Test fun truncatedPartialFinalAndLoopingCursorsNeverBecomeCompleteMembership() = runBlocking {
        val valid = page(listOf(special), 0, 1, true)
        listOf(
            pagination(valid, "truncated", JsonPrimitive(true)),
            pagination(valid, "offset", JsonPrimitive(1)),
            pagination(valid, "totalItems", JsonPrimitive(2)),
            pagination(valid, "complete", JsonPrimitive(false)),
            pagination(valid, "nextCursor", JsonPrimitive(cursor)),
            pagination(valid, "totalItems", JsonPrimitive(10_001)),
            pagination(valid, "offset", JsonPrimitive("0")),
        ).forEach { bad -> assertFailsWith<IllegalStateException> { loadWCoreSeriesMembership(seriesId) { bad } } }
        assertFailsWith<IllegalStateException> {
            loadWCoreSeriesMembership(seriesId) {
                if (it == null) page(listOf(special), 0, 3, false, cursor) else page(listOf(regular), 1, 3, false, cursor)
            }
        }
        Unit
    }

    @Test fun duplicateCanonicalIdsCoordinatesWrongParentAndUnavailableMembersRejectSnapshot() = runBlocking {
        val first = page(listOf(special), 0, 2, false, cursor)
        val last = page(listOf(regular), 1, 2, true)
        listOf(
            item(last, "wMediaId", JsonPrimitive(special.id)),
            page(listOf(regular.copy(season = 0, episode = 1)), 1, 2, true),
            item(last, "seriesWMediaId", JsonPrimitive("wm_otherseries")),
            item(last, "season", JsonPrimitive(-1)),
            item(last, "episode", JsonPrimitive(0)),
            item(last, "season", JsonPrimitive("1")),
            item(last, "availability", buildJsonObject { put("local", true); put("playable", false) }),
        ).forEach { bad -> assertFailsWith<IllegalStateException> { loadWCoreSeriesMembership(seriesId) { if (it == null) first else bad } } }
    }

    @Test fun oversizedPagesAndEndlessSmallPagesStayBounded() = runBlocking {
        val oversized = (1..201).map { WCoreSeriesMember("wm_member${it.toString().padStart(8, '0')}", 1, it, "Episode") }
        assertFailsWith<IllegalStateException> { loadWCoreSeriesMembership(seriesId) { page(oversized, 0, 201, true) } }
        var requests = 0
        assertFailsWith<IllegalStateException> {
            loadWCoreSeriesMembership(seriesId) {
                val position = requests++
                page(listOf(WCoreSeriesMember("wm_member${position.toString().padStart(8, '0')}", 1, position + 1, "Episode")), position, 10_000, false, "cursor_${position.toString().padStart(20, '0')}")
            }
        }
        assertEquals(51, requests)
        assertFailsWith<IllegalStateException> { loadWCoreSeriesMembership(seriesId) { page(listOf(regular, special), 0, 2, true) } }
        Unit
    }

    @Test fun authenticatedClientUsesCanonicalRouteAndRejectsOwnerOrRequestChangesBetweenPages() = runBlocking {
        val paths = mutableListOf<String>()
        val client = WCoreNativeClient({ session }) { captured, path ->
            assertEquals(session, captured)
            paths += path
            RawHttpResponse(200, "test", session.origin, if ("cursor=" in path) page(listOf(regular), 1, 2, true) else page(listOf(special), 0, 2, false, cursor), emptyMap())
        }
        client.series(session, seriesId)
        assertEquals(listOf("/api/wcore/library/series/$seriesId/episodes?limit=200", "/api/wcore/library/series/$seriesId/episodes?limit=200&cursor=$cursor"), paths)
        listOf(session.copy(account = "other"), session.copy(profileId = 2), session.copy(origin = "https://other.example"),
            session.copy(revision = 3), session.copy(token = "renewed-request-token")).forEach { changed ->
            var current = session
            val fenced = WCoreNativeClient({ current }) { _, path ->
                if ("cursor=" in path) current = changed
                RawHttpResponse(200, "test", session.origin, if ("cursor=" in path) page(listOf(regular), 1, 2, true) else page(listOf(special), 0, 2, false, cursor), emptyMap())
            }
            assertFailsWith<IllegalStateException> { fenced.series(session, seriesId) }
        }
    }

    @Test fun cancelledOrFailedPageDoesNotPublishPartialMembership() = runBlocking {
        var published = false
        assertFailsWith<IllegalStateException> {
            loadWCoreSeriesMembership(seriesId) { if (it == null) page(listOf(special), 0, 2, false, cursor) else error("Core unavailable") }
            published = true
        }
        assertFalse(published)
        val job = launch {
            try {
                loadWCoreSeriesMembership(seriesId) { currentCoroutineContext().cancel(); page(listOf(special), 0, 1, true) }
                published = true
            } catch (_: CancellationException) { }
        }
        job.join()
        assertFalse(published)
    }

    @Test fun inventoryCarriesVerifiedParentWithoutChangingExactEpisodeIdentity() {
        val items = parseWCoreLibrary("""{"items":[{"wMediaId":"wm_episode01","type":"episode","title":"Local series","season":0,"episode":1,"seriesWMediaId":"wm_series123","jellyfinSeriesId":"jf-series","seriesMembershipStatus":"verified"}]}""", session.origin)
        assertEquals(special.id, items.single().id)
        assertEquals(seriesId, items.single().seriesWMediaId)
        assertEquals("jf-series", items.single().jellyfinSeriesId)
        assertEquals("verified", items.single().seriesMembershipStatus)
        assertEquals(0, items.single().season)
    }

    @Test fun parentUpgradeRequiresVerifiedJellyfinMembershipAndExactCoordinates() {
        val membership = WCoreSeriesMembership(seriesId, "jf-series", "Local series", listOf(special))
        val episode = WCoreLibraryItem(special.id, "episode", "Local series", season = 0, episode = 1,
            seriesWMediaId = seriesId, jellyfinSeriesId = "jf-series", seriesMembershipStatus = "verified")
        assertTrue(verifiedSeriesContainsEpisode(membership, episode))
        assertFalse(verifiedSeriesContainsEpisode(membership, episode.copy(seriesMembershipStatus = "missing_parent")))
        assertFalse(verifiedSeriesContainsEpisode(membership, episode.copy(jellyfinSeriesId = "different-jf")))
        assertFalse(verifiedSeriesContainsEpisode(membership, episode.copy(id = "wm_different1")))
        assertFalse(verifiedSeriesContainsEpisode(membership, episode.copy(season = 1)))
        assertNull(nativeCoreSeriesDetails(membership, "wm_different1").defaultVideoId)
    }
}
