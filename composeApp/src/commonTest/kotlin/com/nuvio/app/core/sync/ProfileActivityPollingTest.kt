package com.nuvio.app.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileActivityPollingTest {
    @Test
    fun openClientPicksUpRemoteProgressWithinFifteenSecondsAndKeepsRefreshing() = runTest {
        var remotePosition = 120_000L
        var visiblePosition = 0L
        val polling = backgroundScope.launch {
            runPeriodicProfileActivitySync { visiblePosition = remotePosition }
        }
        runCurrent()
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(120_000L, visiblePosition)
        remotePosition = 240_000L
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(240_000L, visiblePosition)
        polling.cancel()
        remotePosition = 360_000L
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(240_000L, visiblePosition)
    }

    @Test
    fun transientFailureDoesNotPermanentlyStopProgressRefresh() = runTest {
        var attempts = 0
        var failures = 0
        var visiblePosition = 0L
        backgroundScope.launch {
            runPeriodicProfileActivitySync(onFailure = { failures++ }) {
                attempts++
                if (attempts == 1) error("connection temporarily unavailable")
                visiblePosition = 120_000L
            }
        }
        runCurrent()
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(1, failures)
        assertEquals(0L, visiblePosition)
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(120_000L, visiblePosition)
    }

    @Test
    fun cancelledAccountRequestIsNeverRetried() = runTest {
        var attempts = 0
        var failures = 0
        val polling = backgroundScope.launch {
            runPeriodicProfileActivitySync(onFailure = { failures++ }) {
                attempts++
                throw CancellationException("account cleared")
            }
        }
        runCurrent()
        advanceTimeBy(30_000L)
        runCurrent()
        assertTrue(polling.isCancelled)
        assertEquals(1, attempts)
        assertEquals(0, failures)
    }
}
