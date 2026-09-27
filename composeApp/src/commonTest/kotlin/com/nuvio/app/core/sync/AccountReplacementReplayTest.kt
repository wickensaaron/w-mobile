package com.nuvio.app.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountReplacementReplayTest {
    private val owner = "https://w.example|account-a"

    @Serializable
    private data class SnapshotWithReplacement(
        val names: List<String> = emptyList(),
        val pending: AccountReplacement<String>? = null,
    )

    @Test
    fun damagedReplacementRetainsLocalSnapshotAndOriginalForRecovery() {
        val raw = """{"names":["saved addon"],"pending":{"owner":"account-a","items":42}}"""
        val recovered = decodeRecoverableAccountPayload<SnapshotWithReplacement>(Json, raw, "pending")
        assertEquals(listOf("saved addon"), recovered.value?.names)
        assertEquals(null, recovered.value?.pending)
        assertEquals(raw, recovered.damagedPayload)
    }

    @Test
    fun malformedWholePayloadIsRetainedWithoutInventingAnOwner() {
        val raw = "{unfinished"
        val recovered = decodeRecoverableAccountPayload<SnapshotWithReplacement>(Json, raw, "pending")
        assertEquals(null, recovered.value)
        assertEquals(raw, recovered.damagedPayload)
    }

    @Test
    fun validReplacementSurvivesDecodeWithoutRecoveryMarker() {
        val pending = AccountReplacement(owner, listOf("saved addon"))
        val raw = Json.encodeToString(SnapshotWithReplacement(listOf("saved addon"), pending))
        val recovered = decodeRecoverableAccountPayload<SnapshotWithReplacement>(Json, raw, "pending")
        assertEquals(pending, recovered.value?.pending)
        assertEquals(null, recovered.damagedPayload)
    }

    @Test
    fun failedReplacementSurvivesReloadAndRunsBeforePull() = runBlocking {
        var stored = Json.encodeToString(AccountReplacement(owner, listOf("addon-b", "addon-a")))
        var connected = false
        val order = mutableListOf<String>()
        suspend fun sync(): Boolean {
            val pending = Json.decodeFromString<AccountReplacement<String>>(stored)
            val ready = replayAccountReplacement(
                pending, { owner }, { true },
                push = { items ->
                    order += "push:${items.joinToString()}"
                    if (!connected) error("offline")
                },
                acknowledge = { stored = "" },
                onFailure = {},
            )
            if (ready) order += "pull"
            return ready
        }
        assertFalse(sync())
        assertTrue(stored.isNotEmpty())
        connected = true
        assertTrue(sync())
        assertEquals(listOf("push:addon-b, addon-a", "push:addon-b, addon-a", "pull"), order)
        assertEquals("", stored)
    }

    @Test
    fun anotherAccountOrBackendAndUnboundLegacyNeverReplay() = runBlocking {
        for (pendingOwner in listOf(null, "https://other.example|account-a", "https://w.example|account-b")) {
            var pushed = false
            val ready = replayAccountReplacement(
                AccountReplacement(pendingOwner, listOf("saved")), { owner }, { true },
                push = { pushed = true }, acknowledge = { error("Unexpected acknowledgement") }, onFailure = {},
            )
            assertFalse(ready)
            assertFalse(pushed)
        }
    }

    @Test
    fun accountSwitchDuringRequestCannotAcknowledgeOrPull() = runBlocking {
        var currentOwner = owner
        var acknowledged = false
        val ready = replayAccountReplacement(
            AccountReplacement(owner, listOf("profile")), { currentOwner }, { true },
            push = { currentOwner = "https://w.example|account-b" },
            acknowledge = { acknowledged = true }, onFailure = {},
        )
        assertFalse(ready)
        assertFalse(acknowledged)
    }

    @Test
    fun newerEditDuringRequestCannotAcknowledgeOrPull() = runBlocking {
        var pending = AccountReplacement(owner, listOf("old"))
        val original = pending
        var acknowledged = false
        val ready = replayAccountReplacement(
            original, { owner }, { pending == original },
            push = { pending = AccountReplacement(owner, listOf("new")) },
            acknowledge = { acknowledged = true }, onFailure = {},
        )
        assertFalse(ready)
        assertFalse(acknowledged)
        assertEquals(listOf("new"), pending.items)
    }

    @Test
    fun removingLastAddonReplaysAnEmptyList() = runBlocking {
        var pushed: List<String>? = null
        var acknowledged = false
        assertTrue(replayAccountReplacement(
            AccountReplacement(owner, emptyList<String>()), { owner }, { true },
            push = { pushed = it }, acknowledge = { acknowledged = true }, onFailure = {},
        ))
        assertEquals(emptyList(), pushed)
        assertTrue(acknowledged)
    }

    @Test
    fun cancellationLeavesIntentPending() = runBlocking {
        var acknowledged = false
        assertFailsWith<CancellationException> {
            replayAccountReplacement(
                AccountReplacement(owner, listOf("profile")), { owner }, { true },
                push = { throw CancellationException() }, acknowledge = { acknowledged = true },
                onFailure = { error("Cancellation must propagate") },
            )
        }
        assertFalse(acknowledged)
    }

    @Test
    fun emptyRemoteSeedingPreservesKnownRemovalsAndLegacyOwnership() {
        assertTrue(canSeedEmptyAccountList(owner, owner, false, false))
        assertTrue(canSeedEmptyAccountList(null, owner, false, true))
        assertFalse(canSeedEmptyAccountList(owner, owner, true, false))
        assertFalse(canSeedEmptyAccountList(null, owner, false, false))
        assertFalse(canSeedEmptyAccountList("other-account", owner, false, true))
    }
}
