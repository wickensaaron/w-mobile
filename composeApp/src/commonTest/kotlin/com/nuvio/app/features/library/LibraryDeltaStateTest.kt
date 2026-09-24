package com.nuvio.app.features.library

import com.nuvio.app.features.library.sync.LibraryDeltaEvent
import com.nuvio.app.features.library.sync.LibrarySyncKey
import com.nuvio.app.features.watching.sync.LegacyUnboundSyncIdentity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryDeltaStateTest {

    @Test
    fun `removing final item records an explicit delete mutation`() {
        val state = loadedState(listOf(libraryItem("item")))

        val result = state.remove(id = "item", type = "movie")

        assertEquals(1, result.affectedCount)
        assertTrue(result.snapshot.items.isEmpty())
        assertTrue(result.snapshot.pendingUpsertKeys.isEmpty())
        assertEquals(listOf("item"), result.snapshot.pendingDeleteKeys.map { it.contentId })
    }

    @Test
    fun `successful push clears only the current mutation snapshot`() {
        val state = loadedState(emptyList())
        val pushedSnapshot = state.upsert(libraryItem("first"))
        state.upsert(libraryItem("second"))

        assertEquals(null, state.markPushCompleted(pushedSnapshot))
        assertEquals(
            setOf("first", "second"),
            state.snapshot().pendingUpsertKeys.mapTo(mutableSetOf()) { it.contentId },
        )
    }

    @Test
    fun `delta deletion clears a remotely managed final item`() {
        val state = loadedState(listOf(libraryItem("item")))
        val bootstrap = assertNotNull(
            state.applyServerItems(
                pullSnapshot = state.snapshot(),
                serverItems = listOf(libraryItem("item")),
                cursorEventId = 20L,
            ),
        )

        val updated = assertNotNull(
            state.applyDeltaEvents(
                token = bootstrap.snapshot.token,
                events = listOf(
                    LibraryDeltaEvent(
                        eventId = 21L,
                        operation = "delete",
                        item = libraryItem("item"),
                    ),
                ),
            ),
        )

        assertTrue(updated.items.isEmpty())
        assertEquals(21L, updated.deltaCursorEventId)
        assertTrue(updated.deltaInitialized)
    }

    @Test
    fun `storage payload keeps cursor and pending deletes across reload`() {
        val state = loadedState(listOf(libraryItem("item")))
        val bootstrap = assertNotNull(
            state.applyServerItems(
                pullSnapshot = state.snapshot(),
                serverItems = listOf(libraryItem("item")),
                cursorEventId = 42L,
            ),
        )
        val removed = state.remove(id = "item", type = "movie").snapshot
        val stored = LibraryStoragePayloadCodec.decode(
            LibraryStoragePayloadCodec.encode(removed),
        )
        val reloaded = LibraryLocalState()
        val token = reloaded.beginProfileLoad(profileId = 1).snapshot.token
        val snapshot = assertNotNull(
            reloaded.completeProfileLoad(
                token = token,
                activeProfileId = 1,
                items = stored.items,
                deltaCursorEventId = stored.deltaCursorEventId,
                deltaInitialized = stored.deltaInitialized,
                pendingUpsertKeys = stored.pendingUpsertKeys,
                pendingDeleteKeys = stored.pendingDeleteKeys,
            ),
        )

        assertTrue(bootstrap.snapshot.deltaInitialized)
        assertEquals(42L, snapshot.deltaCursorEventId)
        assertTrue(snapshot.deltaInitialized)
        assertEquals(listOf("item"), snapshot.pendingDeleteKeys.map { it.contentId })
    }

    @Test
    fun `legacy storage payload decodes with delta sync disabled`() {
        val stored = LibraryStoragePayloadCodec.decode(
            """{"items":[{"id":"legacy","type":"movie","name":"Legacy","savedAtEpochMs":1}]}""",
        )

        assertEquals(listOf("legacy"), stored.items.map(LibraryItem::id))
        assertEquals(0L, stored.deltaCursorEventId)
        assertFalse(stored.deltaInitialized)
        assertTrue(stored.pendingUpsertKeys.isEmpty())
        assertTrue(stored.pendingDeleteKeys.isEmpty())
    }

    @Test
    fun `reloading a pending delete keeps the item removed`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        val snapshot = assertNotNull(
            state.completeProfileLoad(
                token = token,
                activeProfileId = 1,
                items = listOf(libraryItem("item")),
                deltaInitialized = true,
                pendingDeleteKeys = listOf(
                    LibrarySyncKey(contentId = "item", contentType = "movie"),
                ),
            ),
        )

        assertTrue(snapshot.items.isEmpty())
        assertEquals(listOf("item"), snapshot.pendingDeleteKeys.map { it.contentId })
    }

    @Test
    fun `pending library writes stay with their backend and account`() {
        val original = StoredLibraryPayload(
            items = listOf(libraryItem("saved")),
            pendingUpsertKeys = listOf(LibrarySyncKey("saved", "movie")),
            pendingDeleteKeys = listOf(LibrarySyncKey("removed", "movie")),
            syncIdentity = "https://server-a|user-a",
        )
        val switched = original.forSyncIdentity("https://server-b|user-b")
        val reloaded = LibraryStoragePayloadCodec.decode(Json.encodeToString(switched))
            .forSyncIdentity("https://server-a|user-a")

        assertTrue(switched.items.isEmpty())
        assertTrue(switched.pendingUpsertKeys.isEmpty())
        assertTrue(switched.pendingDeleteKeys.isEmpty())
        assertEquals(listOf("saved"), reloaded.pendingUpsertKeys.map { it.contentId })
        assertEquals(listOf("removed"), reloaded.pendingDeleteKeys.map { it.contentId })
    }

    @Test
    fun `new account save retains the original account queue in the same payload`() {
        val original = StoredLibraryPayload(
            items = listOf(libraryItem("from-a")),
            pendingUpsertKeys = listOf(LibrarySyncKey("from-a", "movie")),
            syncIdentity = "https://server-a|user-a",
        )
        val selected = original.forSyncIdentity("https://server-b|user-b")
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(1, selected.syncIdentity).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = selected.items,
            otherIdentities = selected.otherIdentities,
        )
        state.upsert(libraryItem("from-b"))
        val returned = LibraryStoragePayloadCodec.decode(
            LibraryStoragePayloadCodec.encode(state.snapshot()),
        ).forSyncIdentity("https://server-a|user-a")

        assertEquals(listOf("from-a"), returned.pendingUpsertKeys.map { it.contentId })
        assertEquals(listOf("from-a"), returned.items.map { it.id })
    }

    @Test
    fun `legacy pending writes are retained without being assigned to a new account`() {
        val selected = StoredLibraryPayload(
            items = listOf(libraryItem("saved")),
            pendingUpsertKeys = listOf(LibrarySyncKey("saved", "movie")),
        ).forSyncIdentity("https://new-server|new-user")

        assertTrue(selected.items.isEmpty())
        assertTrue(selected.pendingUpsertKeys.isEmpty())
        assertEquals(
            listOf("saved"),
            selected.otherIdentities.getValue(LegacyUnboundSyncIdentity).pendingUpsertKeys.map { it.contentId },
        )
    }

    @Test
    fun `signed out view does not expose a previous account library`() {
        val signedIn = StoredLibraryPayload(
            items = listOf(libraryItem("private")),
            syncIdentity = "https://server-a|user-a",
        )
        val signedOut = signedIn.forSyncIdentity(null)

        assertTrue(signedOut.items.isEmpty())
        assertEquals(
            listOf("private"),
            signedOut.forSyncIdentity("https://server-a|user-a").items.map { it.id },
        )
    }

    private fun loadedState(items: List<LibraryItem>): LibraryLocalState {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = items,
        )
        return state
    }

    private fun libraryItem(id: String): LibraryItem =
        LibraryItem(
            id = id,
            type = "movie",
            name = id,
            savedAtEpochMs = 1L,
        )
}
