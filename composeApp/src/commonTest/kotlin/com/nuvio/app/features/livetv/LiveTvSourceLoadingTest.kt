package com.nuvio.app.features.livetv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTvSourceLoadingTest {
    private val owner = LiveTvAccountScope("https://account.example.invalid", "owner-a", 1)
    private val source = ImportedLiveTvSource("source-a", "Test", "XTREAM", "https://provider.example.invalid", "u", "p")
    private fun channel(id: String, stream: String = "https://stream.example.invalid/$id") =
        LiveTvChannel(id, "UK: Channel $id", stream, playlistId = source.id, guideId = "guide-$id", accountScope = owner)

    @Test
    fun fastProviderPublishesBeforeSlowFailureAndOtherProvidersKeepLoading() = runTest {
        val seen = mutableListOf<Pair<Int, Long>>()
        var running = 0
        var peak = 0
        val tasks = listOf<suspend () -> Int>(
            { running++; peak = maxOf(peak, running); delay(60_000); running--; error("offline") },
            { running++; peak = maxOf(peak, running); delay(100); running--; 1 },
            { running++; peak = maxOf(peak, running); delay(200); running--; 2 },
        )
        loadLiveTvSources(tasks, parallelism = 2, dispatcher = StandardTestDispatcher(testScheduler)) { index, _ ->
            seen += index to currentTime
        }
        assertEquals(listOf(1 to 100L, 2 to 300L, 0 to 60_000L), seen)
        assertEquals(2, peak)
    }

    @Test
    fun sourceCancellationPropagatesWithoutWaitingForeverForAMissingCompletion() = runTest {
        assertFailsWith<CancellationException> {
            loadLiveTvSources(listOf<suspend () -> Int>({ throw CancellationException("account changed") }),
                dispatcher = StandardTestDispatcher(testScheduler)) { _, _ -> error("must not publish") }
        }
    }

    @Test
    fun cancellingCallerStopsItsInFlightProviders() = runTest {
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        val job = async {
            loadLiveTvSources(listOf<suspend () -> Int>({
                entered.complete(Unit)
                try { awaitCancellation() } finally { stopped = true }
            }), dispatcher = StandardTestDispatcher(testScheduler)) { _, _ -> error("must not publish") }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(stopped)
    }

    @Test
    fun channelPriorityAndPlaybackGenerationDoNotDependOnArrivalOrder() = runTest {
        val first = channel("source-a:1")
        val backup = first.copy(name = "Backup")
        val local = channel("manual:1").copy(accountScope = null)
        val duplicateLocal = local.copy(id = "manual:2")
        val partial = mergeLiveTvChannelLoads(listOf(null, listOf(backup, local, duplicateLocal)), 9)
        assertEquals(listOf("Backup", local.name), partial.map { it.name })
        val complete = mergeLiveTvChannelLoads(listOf(listOf(first), listOf(backup, local, duplicateLocal)), 9)
        assertEquals(listOf(first.name, local.name), complete.map { it.name })
        assertTrue(complete.all { it.sourceLoadGeneration == 9 })
        assertEquals(first.streamUrl, complete.first().streamUrl)
    }

    @Test
    fun guideCacheExpiresAndCannotCrossAccountProfileSourceOrGuideIdentity() {
        val cache = LiveTvGuideMemoryCache()
        val channels = listOf(channel("source-a:1"))
        val guide = ImportedXmlTvResult(mapOf(channels.single().id to listOf(
            LiveTvProgramme(channels.single().id, "News", startEpochMs = 1, stopEpochMs = 1000))), false)
        cache.put(owner, source, channels, 100, 1000, guide)
        assertNotNull(cache.get(owner, source, channels, 100, 1001))
        assertNull(cache.get(owner, source.copy(password = "changed"), channels, 100, 1001))
        assertNull(cache.get(owner, source, channels.map { it.copy(guideId = "changed") }, 100, 1001))
        assertNull(cache.get(owner, source, channels, 99, 1001))
        assertNull(cache.get(owner, source, channels, 100, 1000 + 15 * 60_000))
        assertNull(cache.get(owner.copy(profile = 2), source, channels, 100, 1001))
        assertNull(cache.get(owner, source, channels, 100, 1001))
        cache.put(owner, source, channels, 100, 1000, guide)
        assertNull(cache.get(owner.copy(account = "owner-b"), source, channels, 100, 1001))
        cache.put(owner, source, channels, 100, 1000, guide)
        cache.clear()
        assertNull(cache.get(owner, source, channels, 100, 1001))
    }
}
