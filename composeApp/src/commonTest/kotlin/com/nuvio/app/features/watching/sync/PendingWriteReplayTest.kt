package com.nuvio.app.features.watching.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingWriteReplayTest {
    @Test
    fun failedWritesRemainPendingAndReplayBeforeRemoteRead() = runBlocking {
        val pendingDeletes = mutableSetOf("deleted")
        val pendingUpserts = mutableSetOf("updated")
        val remote = mutableSetOf("deleted")
        val order = mutableListOf<String>()
        var connected = false
        var failures = 0

        suspend fun replay() = replayPendingWrites(
            deletes = pendingDeletes.toList(),
            upserts = pendingUpserts.toList(),
            isCurrent = { true },
            isPendingDelete = pendingDeletes::contains,
            isPendingUpsert = pendingUpserts::contains,
            delete = { item ->
                order += "delete:$item"
                if (!connected) error("offline")
                remote -= item
            },
            upsert = { item ->
                order += "upsert:$item"
                if (!connected) error("offline")
                remote += item
            },
            acknowledgeDelete = { pendingDeletes -= it },
            acknowledgeUpsert = { pendingUpserts -= it },
            onFailure = { failures += 1 },
        )

        replay()
        assertEquals(setOf("deleted"), pendingDeletes)
        assertEquals(setOf("updated"), pendingUpserts)
        assertEquals(2, failures)

        connected = true
        replay()
        order += "pull:${remote.sorted().joinToString()}"
        assertEquals(
            listOf("delete:deleted", "upsert:updated", "delete:deleted", "upsert:updated", "pull:updated"),
            order,
        )
        assertEquals(emptySet(), pendingDeletes)
        assertEquals(emptySet(), pendingUpserts)
    }

    @Test
    fun oldResponseDoesNotAcknowledgeNewerLocalUpdate() = runBlocking {
        var pending = "version-1"
        replayPendingWrites(
            deletes = emptyList(),
            upserts = listOf(pending),
            isCurrent = { true },
            isPendingDelete = { false },
            isPendingUpsert = { it == pending },
            delete = {},
            upsert = { pending = "version-2" },
            acknowledgeDelete = {},
            acknowledgeUpsert = { sent -> if (pending == sent) pending = "" },
            onFailure = { throw it },
        )

        assertEquals("version-2", pending)
    }

    @Test
    fun accountChangeStopsReplay() = runBlocking {
        var current = true
        val calls = mutableListOf<String>()
        replayPendingWrites(
            deletes = listOf("first", "second"),
            upserts = listOf("third"),
            isCurrent = { current },
            isPendingDelete = { true },
            isPendingUpsert = { true },
            delete = { item -> calls += item; current = false },
            upsert = { calls += it },
            acknowledgeDelete = {},
            acknowledgeUpsert = {},
            onFailure = { throw it },
        )
        assertEquals(listOf("first"), calls)
    }
}
