package com.nuvio.app.features.watchprogress

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ResumePromptSelectionTest {
    private val local = entry("old-device-video", 100L)
    private val remote = entry("latest-other-device-video", 200L)

    @Test
    fun `waits for sync and selects other device activity instead of stale local state`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var state = WatchProgressUiState(entries = listOf(local))
        val result = async {
            loadLatestResumePromptEntry(
                refreshProgress = {
                    started.complete(Unit)
                    release.await()
                    state = state.copy(entries = listOf(remote, local))
                },
                isCurrentContext = { true },
                readProgress = { state },
            )
        }
        started.await()
        assertFalse(result.isCompleted)
        release.complete(Unit)
        assertEquals(remote, result.await())
    }

    @Test
    fun `fresh install can resume remote activity with no local playback`() = runTest {
        var state = WatchProgressUiState()
        assertEquals(remote, loadLatestResumePromptEntry(
            refreshProgress = { state = state.copy(entries = listOf(remote)) },
            isCurrentContext = { true },
            readProgress = { state },
        ))
    }

    @Test
    fun `newest timestamp wins even when user rewound on another device`() = runTest {
        val rewound = remote.copy(videoId = local.videoId, lastPositionMs = 10_000L)
        assertEquals(rewound, select(listOf(local, rewound)))
        assertEquals(rewound, select(listOf(rewound, local)))
    }

    @Test
    fun `completed latest episode stays selected for next episode resolution`() = runTest {
        val completed = remote.copy(isCompleted = true, seasonNumber = 2, episodeNumber = 4)
        assertEquals(completed, select(listOf(local, completed)))
    }

    @Test
    fun `completed movie does not revive an older unfinished title`() = runTest {
        val completed = remote.copy(isCompleted = true)
        assertEquals(completed, select(listOf(local, completed)))
    }

    @Test
    fun `hidden activity is not offered`() = runTest {
        assertEquals(local, loadLatestResumePromptEntry({}, { true }) {
            WatchProgressUiState(entries = listOf(local, remote), hiddenContentIds = setOf(remote.parentMetaId))
        })
    }

    @Test
    fun `empty account has no prompt`() = runTest {
        assertNull(select(emptyList()))
    }

    @Test
    fun `offline sync failure uses cached account activity`() = runTest {
        assertEquals(remote, loadLatestResumePromptEntry(
            refreshProgress = { error("Offline") },
            isCurrentContext = { true },
            readProgress = { WatchProgressUiState(entries = listOf(local, remote)) },
        ))
    }

    @Test
    fun `hung sync times out and uses cache`() = runTest {
        assertEquals(local, loadLatestResumePromptEntry(
            refreshProgress = { awaitCancellation() },
            isCurrentContext = { true },
            readProgress = { WatchProgressUiState(entries = listOf(local)) },
        ))
        assertEquals(8_000L, testScheduler.currentTime)
    }

    @Test
    fun `switching account or profile while syncing discards pending prompt`() = runTest {
        var current = true
        assertNull(loadLatestResumePromptEntry(
            refreshProgress = { current = false },
            isCurrentContext = { current },
            readProgress = { error("Must not read another profile's activity") },
        ))
    }

    @Test
    fun `leaving home cancels loading without falling back to stale progress`() = runTest {
        assertFailsWith<CancellationException> {
            loadLatestResumePromptEntry(
                refreshProgress = { throw CancellationException("Playback started") },
                isCurrentContext = { true },
                readProgress = { error("Cancelled prompt must not read progress") },
            )
        }
    }

    private suspend fun select(entries: List<WatchProgressEntry>) =
        loadLatestResumePromptEntry({}, { true }) { WatchProgressUiState(entries = entries) }

    private fun entry(id: String, time: Long) = WatchProgressEntry(
        contentType = "movie", parentMetaId = id, parentMetaType = "movie", videoId = id,
        title = id, lastPositionMs = 60_000L, durationMs = 600_000L, lastUpdatedEpochMs = time,
    )
}
