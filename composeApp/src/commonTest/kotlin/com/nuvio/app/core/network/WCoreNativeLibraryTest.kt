package com.nuvio.app.core.network

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.details.seriesPrimaryAction
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.streams.canonicalCorePlaybackBody
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WCoreNativeLibraryTest {
    private val origin = "https://core.example"
    private val session = WCoreLibraryScope("https://auth.example|account-a", 2, origin, "test-session")
    private val bootstrap = """{"capabilities":{"providers":{"jellyfin":{"enabled":true}}}}"""
    private val inventory = """{"items":[
        {"wMediaId":"wm_movie123","type":"movie","title":"Local film","year":2024,
         "posterUrl":"https://core.example/api/wcore/image/test-ticket/primary",
         "playbackUrl":"https://core.example/api/wcore/stream/never-store-this",
         "jellyfin":{"playbackExpiresAt":"2099-01-01T00:00:00Z"}},
        {"wMediaId":"wm_episode12","type":"episode","title":"Local series","season":2,"episode":5,"episodeTitle":"Fifth episode"}
    ]}"""
    private fun response(body: String, status: Int = 200) = RawHttpResponse(status, "test", origin, body, emptyMap())

    @Test
    fun tokenRenewalRetainsLogicalOwnershipButLifecycleChangesDoNot() {
        val item = WCoreLibraryItem("wm_movie123", "movie", "Local")
        assertTrue(nativeCorePlayable(item.id, item, session.owner, session.copy(token = "renewed")))
        assertFalse(nativeCorePlayable(item.id, item, session.owner, session.copy(revision = session.revision + 1)))
    }

    @Test
    fun authenticatedInventoryUsesBootstrapAndVideoRoute() = runBlocking {
        val paths = mutableListOf<String>()
        val client = WCoreNativeClient({ session }) { captured, path ->
            assertEquals(session, captured)
            paths += path
            response(if (path.endsWith("bootstrap")) bootstrap else inventory)
        }
        val items = client.library(session)
        assertEquals(listOf("/api/v1/bootstrap", "/api/wcore/library"), paths)
        assertEquals(listOf("wm_movie123", "wm_episode12"), items.map { it.id })
        assertTrue(items.none { it.toString().contains("never-store-this") })
    }

    @Test
    fun accountOrOriginChangeDuringResponseRejectsInventory() = runBlocking {
        for (changed in listOf(session.copy(account = "another-account"), session.copy(origin = "https://other.example"), session.copy(profileId = 3))) {
            var current = session
            val client = WCoreNativeClient({ current }) { _, _ -> current = changed; response(bootstrap) }
            assertFailsWith<IllegalStateException> { client.library(session) }
        }
    }

    @Test
    fun disabledProviderMakesNoLibraryRequest() = runBlocking {
        val paths = mutableListOf<String>()
        val client = WCoreNativeClient({ session }) { _, path -> paths += path; response("""{"capabilities":{"providers":{"jellyfin":{"enabled":false}}}}""") }
        assertTrue(client.library(session).isEmpty())
        assertEquals(listOf("/api/v1/bootstrap"), paths)
    }

    @Test
    fun exactEpisodeIdentityReachesMetadataAndExistingEpisodeAction() = runBlocking {
        val episode = parseWCoreLibrary(inventory, origin)[1]
        var path = ""
        val client = WCoreNativeClient({ session }) { _, requestPath -> path = requestPath; response("{}") }
        client.detail(session, episode)
        assertEquals("/api/wcore/metadata/detail?type=episode&wMediaId=wm_episode12&season=2&episode=5", path)
        val meta = nativeCoreDetails(episode, null) { null }
        assertEquals("wm_episode12", meta.videos.single().id)
        assertEquals(2, meta.videos.single().season)
        assertEquals(5, meta.videos.single().episode)
        val action = meta.seriesPrimaryAction(emptyList(), emptyList(), "2026-09-27")
        assertEquals(2, action?.seasonNumber)
        assertEquals(5, action?.episodeNumber)
        assertEquals("wm_episode12", action?.videoId)
        val playback = Json.parseToJsonElement(canonicalCorePlaybackBody(episode.id, "en")) as JsonObject
        assertEquals("\"wm_episode12\"", playback["mediaId"].toString())
        assertFalse(playback.containsKey("externalIds"))
    }

    @Test
    fun partialOrUnavailableMetadataKeepsNativeFilmPlayableWithoutExternalIds() = runBlocking {
        val film = parseWCoreLibrary(inventory, origin).first()
        val client = WCoreNativeClient({ session }) { _, _ -> response("{}", 503) }
        assertNull(client.detail(session, film))
        val meta = nativeCoreDetails(film, Json.parseToJsonElement("""{"overview":"Local description"}""") as JsonObject) { "wcore-art://owned/${film.id}/$it" }
        assertEquals("wm_movie123", meta.id)
        assertEquals("Local film", meta.name)
        assertEquals("Local description", meta.description)
        assertTrue(nativeCorePlayable(meta.id, film, session.owner, session))
        val saved = meta.toLibraryItem(1)
        assertFalse(saved.toString().contains("test-ticket"))
        assertFalse(saved.toString().contains("never-store-this"))
    }

    @Test
    fun nativePlayRequiresExactOwnedCanonicalInventoryAndConnectedSession() {
        val film = parseWCoreLibrary(inventory, origin).first()
        assertFalse(nativeCorePlayable(film.id, film, session.owner, null))
        assertFalse(nativeCorePlayable(film.id, film, session.owner, session.copy(profileId = 1)))
        assertFalse(nativeCorePlayable("wm_different1", film, session.owner, session))
        assertFalse(nativeCorePlayable("tt1234567", film, session.owner, session))
        assertTrue(nativeCorePlayable(film.id, film, session.owner, session))
    }

    @Test
    fun logicalArtworkSurvivesRestartAndTokenRenewalButIsAccountProfileAndOriginOwned() {
        val referenceScope = coreArtworkScope(session)
        assertEquals(referenceScope, coreArtworkScope(session.copy(token = "renewed-session")))
        assertEquals(referenceScope, coreArtworkScope(session.copy()))
        assertFalse(referenceScope == coreArtworkScope(session.copy(account = "other-account")))
        assertFalse(referenceScope == coreArtworkScope(session.copy(profileId = 3)))
        assertFalse(referenceScope == coreArtworkScope(session.copy(origin = "https://other.example")))
        assertFalse(referenceScope.contains("test-session"))
    }

    @Test
    fun optionalCoreOfflineFailureStaysWithinNativeRequest() = runBlocking {
        val client = WCoreNativeClient({ session }) { _, _ -> error("offline") }
        assertFailsWith<IllegalStateException> { client.library(session) }
        // The UI's separate native refresh catches this; ordinary HomeRepository is never involved.
        val film = parseWCoreLibrary(inventory, origin).first()
        assertNull(client.detail(session, film))
    }

    @Test
    fun malformedEpisodesAndForeignImageTicketsAreRejected() {
        val items = parseWCoreLibrary("""{"items":[
            {"wMediaId":"wm_valid123","type":"episode","title":"Broken episode","season":1},
            {"wMediaId":"wm_movie456","type":"movie","title":"Safe film","posterUrl":"https://evil.example/api/wcore/image/key/primary"},
            {"wMediaId":"tt1234567","type":"movie","title":"External only"}
        ]}""", origin)
        assertEquals(1, items.size)
        assertNull(items.single().posterTicket)
        assertNull(coreImageTicket("$origin.evil.example/api/wcore/image/key/primary", origin))
    }
}
