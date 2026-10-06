package com.nuvio.app.features.profiles

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileSwitchCoordinatorTest {
    @Test fun queuedTransitionsFinishBeforeTheirCallersContinue() = runBlocking {
        val coordinator = ProfileSwitchCoordinator<String>()
        val events = mutableListOf<String>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.switch("account", { "account" }) { events += "reset:1" }.also { events += "activate:1" }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.switch("account", { "account" }) { events += "reset:2" }.also { events += "activate:2" }
        }
        assertTrue(events.isEmpty()) // Loading state can be published before the first reset.
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(listOf("reset:1", "activate:1", "reset:2", "activate:2"), events)
    }

    @Test fun accountChangeBeforeDispatchRejectsOldRequest() = runBlocking {
        val coordinator = ProfileSwitchCoordinator<Pair<Long, String?>>()
        var owner = 1L to "alice"
        var resets = 0
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.switch(owner, { owner }) { resets++ }
        }
        owner = 2L to "bob"
        assertFalse(request.await())
        assertEquals(0, resets)
    }

    @Test fun sameAccountNewGenerationAlsoRejectsQueuedRequest() = runBlocking {
        val coordinator = ProfileSwitchCoordinator<Pair<Long, String?>>()
        var owner = 1L to "alice"
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.switch(owner, { owner }) { error("Must not reset") }
        }
        owner = 2L to "alice"
        assertFalse(request.await())
    }

    @Test fun cancelledQueuedRequestDoesNotResetOrBlockNextRequest() = runBlocking {
        val coordinator = ProfileSwitchCoordinator<String>()
        val events = mutableListOf<Int>()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.switch("owner", { "owner" }) { events += 1 }
        }
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.switch("owner", { "owner" }) { events += 2 }
        }
        cancelled.cancel()
        cancelled.join()
        assertTrue(next.await())
        assertEquals(listOf(2), events)
    }

    @Test fun failureReleasesCoordinatorForLaterTransition() = runBlocking {
        val coordinator = ProfileSwitchCoordinator<String>()
        val failure = runCatching { coordinator.switch("owner", { "owner" }) { error("Storage failure") } }
        assertTrue(failure.isFailure)
        var reset = false
        assertTrue(coordinator.switch("owner", { "owner" }) { reset = true })
        assertTrue(reset)
    }
}
