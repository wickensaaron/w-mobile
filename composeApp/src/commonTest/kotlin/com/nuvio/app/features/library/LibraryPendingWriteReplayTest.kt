package com.nuvio.app.features.library

import com.nuvio.app.features.library.sync.LibraryDeltaEvent
import com.nuvio.app.features.library.sync.LibrarySyncAdapter
import com.nuvio.app.features.library.sync.LibrarySyncKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryPendingWriteReplayTest {
    private val identity = "https://server-a|user-a"

    @Test
    fun offlineFailureSurvivesProcessReloadAndReplaysBeforePull() = runBlocking {
        val original = loadedState(listOf(item("deleted", 1L)))
        original.remove("deleted", "movie")
        original.upsert(item("added", 2L))
        val stored = LibraryStoragePayloadCodec.decode(LibraryStoragePayloadCodec.encode(original.snapshot()))
        val reloaded = loadedState(
            items = stored.items,
            pendingUpserts = stored.pendingUpsertKeys,
            pendingDeletes = stored.pendingDeleteKeys,
        )
        val remote = mutableSetOf("deleted")
        val order = mutableListOf<String>()
        var connected = false
        val adapter = FakeAdapter(
            onPush = { entries ->
                order += "upsert:${entries.single().id}"
                if (!connected) error("offline")
                remote += entries.single().id
            },
            onDelete = { keys ->
                order += "delete:${keys.single().contentId}"
                if (!connected) error("offline")
                remote -= keys.single().contentId
            },
        )
        var persisted = ""
        suspend fun replay() = replayLibraryPendingWrites(
            state = reloaded,
            token = reloaded.snapshot().token,
            adapter = adapter,
            isCurrentAccount = { true },
            onAcknowledged = { persisted = LibraryStoragePayloadCodec.encode(it) },
            onFailure = {},
        )

        replay()
        assertEquals(1, reloaded.snapshot().pendingUpsertKeys.size)
        assertEquals(1, reloaded.snapshot().pendingDeleteKeys.size)

        connected = true
        replay()
        order += "pull"
        assertEquals(
            listOf("delete:deleted", "upsert:added", "delete:deleted", "upsert:added", "pull"),
            order,
        )
        assertEquals(setOf("added"), remote)
        assertTrue(reloaded.snapshot().pendingUpsertKeys.isEmpty())
        assertTrue(reloaded.snapshot().pendingDeleteKeys.isEmpty())
        assertTrue(LibraryStoragePayloadCodec.decode(persisted).pendingDeleteKeys.isEmpty())
    }

    @Test
    fun staleUpsertResponseCannotAcknowledgeNewerLocalItem() = runBlocking {
        val state = loadedState(emptyList())
        state.upsert(item("film", 1L))
        replayLibraryPendingWrites(
            state = state,
            token = state.snapshot().token,
            adapter = FakeAdapter(onPush = { state.upsert(item("film", 2L)) }),
            isCurrentAccount = { true },
            onAcknowledged = {},
            onFailure = { throw it },
        )

        assertEquals(2L, state.snapshot().items.single().savedAtEpochMs)
        assertEquals(listOf("film"), state.snapshot().pendingUpsertKeys.map { it.contentId })
    }

    @Test
    fun staleDeleteResponseCannotAcknowledgeASecondDeleteOfSameKey() = runBlocking {
        val state = loadedState(listOf(item("film", 1L)))
        state.remove("film", "movie")
        replayLibraryPendingWrites(
            state = state,
            token = state.snapshot().token,
            adapter = FakeAdapter(onDelete = {
                state.upsert(item("film", 2L))
                state.remove("film", "movie")
            }),
            isCurrentAccount = { true },
            onAcknowledged = {},
            onFailure = { throw it },
        )

        assertEquals(listOf("film"), state.snapshot().pendingDeleteKeys.map { it.contentId })
        assertTrue(state.snapshot().items.isEmpty())
    }

    @Test
    fun accountChangeStopsReplayWithoutAcknowledgingOldWrites() = runBlocking {
        val state = loadedState(listOf(item("deleted", 1L)))
        state.remove("deleted", "movie")
        state.upsert(item("added", 2L))
        var sameAccount = true
        val calls = mutableListOf<String>()
        replayLibraryPendingWrites(
            state = state,
            token = state.snapshot().token,
            adapter = FakeAdapter(
                onDelete = {
                    calls += "delete"
                    sameAccount = false
                },
                onPush = { calls += "upsert" },
            ),
            isCurrentAccount = { sameAccount },
            onAcknowledged = {},
            onFailure = { throw it },
        )

        assertEquals(listOf("delete"), calls)
        assertEquals(1, state.snapshot().pendingDeleteKeys.size)
        assertEquals(1, state.snapshot().pendingUpsertKeys.size)
    }

    private fun loadedState(
        items: List<LibraryItem>,
        pendingUpserts: List<LibrarySyncKey> = emptyList(),
        pendingDeletes: List<LibrarySyncKey> = emptyList(),
    ): LibraryLocalState = LibraryLocalState().also { state ->
        val token = state.beginProfileLoad(1, identity).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = items,
            pendingUpsertKeys = pendingUpserts,
            pendingDeleteKeys = pendingDeletes,
        )
    }

    private fun item(id: String, savedAt: Long) =
        LibraryItem(id = id, type = "movie", name = id, savedAtEpochMs = savedAt)

    private class FakeAdapter(
        val onPush: suspend (Collection<LibraryItem>) -> Unit = {},
        val onDelete: suspend (Collection<LibrarySyncKey>) -> Unit = {},
    ) : LibrarySyncAdapter {
        override suspend fun pullSnapshot(profileId: Int, pageSize: Int): List<LibraryItem> = emptyList()
        override suspend fun getDeltaCursor(profileId: Int): Long = 0L
        override suspend fun pullDelta(profileId: Int, sinceEventId: Long, limit: Int): List<LibraryDeltaEvent> =
            emptyList()
        override suspend fun pushItems(profileId: Int, items: Collection<LibraryItem>) = onPush(items)
        override suspend fun deleteItems(profileId: Int, keys: Collection<LibrarySyncKey>) = onDelete(keys)
    }
}
