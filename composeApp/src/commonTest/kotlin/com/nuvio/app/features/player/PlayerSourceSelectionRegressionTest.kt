package com.nuvio.app.features.player

import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerPlaybackTarget
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PlayerSourceSelectionRegressionTest {
    @Test fun slowOlderChoiceCannotReplaceANewerChoice() = runTest {
        val gate = PlayerSourceRequestGate()
        val response = CompletableDeferred<String>()
        val first = gate.begin()
        var selected = "initial"
        val oldRequest = launch {
            val result = response.await()
            if (gate.isCurrent(first)) selected = result
        }
        val second = gate.begin()
        if (gate.isCurrent(second)) selected = "new episode"
        response.complete("old episode")
        oldRequest.join()
        assertEquals("new episode", selected)
        gate.invalidate()
        assertFalse(gate.isCurrent(second))
    }

    @Test fun nativeServerCandidateIsPreparedBeforeItsUrlIsRequired() = runTest {
        val candidate = StreamItem(addonName = "Jellyfin", addonId = "server:test",
            serverTarget = ServerPlaybackTarget(ServerItemRef("test", "episode2"), null))
        assertNull(candidate.playableDirectUrl)
        var prepared = false
        val playable = prepareNextEpisodeServerStream(candidate) {
            prepared = true
            it.copy(url = "https://example.invalid/episode2.mkv")
        }
        assertTrue(prepared)
        assertEquals("https://example.invalid/episode2.mkv", playable?.playableDirectUrl)
    }

    @Test fun directSourcesDoNotCreateMediaServerSessions() = runTest {
        val direct = StreamItem(addonName = "Addon", addonId = "addon:test", url = "https://example.invalid/video")
        assertEquals(direct, prepareNextEpisodeServerStream(direct) { error("Unexpected preparation") })
    }

    @Test fun failedServerCandidateAllowsFallbackButCancellationPropagates() = runTest {
        val candidate = StreamItem(addonName = "Jellyfin", addonId = "server:test",
            serverTarget = ServerPlaybackTarget(ServerItemRef("test", "episode2"), null))
        assertNull(prepareNextEpisodeServerStream(candidate) { error("Unavailable") })
        assertFailsWith<CancellationException> {
            prepareNextEpisodeServerStream(candidate) { throw CancellationException("Selection changed") }
        }
    }
}
