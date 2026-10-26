package com.nuvio.app.features.party

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PartyServerStateTest {
    private fun state(seq: Long, member: String) = PartyPlayback(
        seq = seq, memberId = member, paused = true, positionMs = 1000L, at = 2000L,
    )

    @Test fun rejectedOptimisticActionCannotOverrideTheServerWinner() {
        val lastConfirmed = state(1, "host")
        val optimistic = state(2, "z-member")
        val serverWinner = state(2, "a-member")
        assertFalse(PartySync.isNewer(serverWinner, optimistic))
        assertTrue(PartySync.acceptsServerState(serverWinner, lastConfirmed))
    }

    @Test fun oldAcknowledgementsCannotRollBackANewerConfirmedState() {
        assertFalse(PartySync.acceptsServerState(state(2, "z"), state(3, "a")))
        assertTrue(PartySync.acceptsServerState(state(3, "a"), state(3, "a")))
        assertTrue(PartySync.acceptsServerState(state(1, "a"), null))
    }

    @Test fun terminalServerErrorsAreRecognizedWithoutTreatingNetworkFailuresAsRemoval() {
        for (code in listOf("not_member", "party_ended", "unavailable")) {
            assertEquals(code, partyTerminalRpcError(" $code "))
        }
        assertNull(partyTerminalRpcError("timeout"))
        assertNull(partyTerminalRpcError("rate_limited"))
    }
}
