package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.streams.StreamSubtitle
import com.nuvio.app.features.streams.shouldRecoverWCorePlayerSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import kotlin.test.*

class PlayerWCorePlaybackTest {
    private val key = PlaybackKey("old-signed-url", "wm_episode12", 2, 5)
    private val refreshed = StreamItem(url = "https://core.example/api/v1/playback/stream/new-ticket", sourceName = "jellyfin:item",
        addonName = "W Core", addonId = "wcore:jellyfin", coreSelectionReference = "wcore-source://1",
        externalSubtitles = listOf(StreamSubtitle("https://core.example/subtitle", "en", headers = mapOf("Authorization" to "Bearer fresh"))),
        behaviorHints = StreamBehaviorHints(proxyHeaders = StreamProxyHeaders(request = mapOf("Authorization" to "Bearer fresh"))))

    @Test fun playerFenceRejectsProfileSwitchReselectAndEpisodeChange() {
        val fence = CorePlayerRequestFence(1, key, 4)
        assertTrue(fence.matches(1, key, 4))
        assertFalse(fence.matches(2, key, 4))
        assertFalse(fence.matches(1, key, 5))
        assertFalse(fence.matches(1, key.copy(sourceIdentity = "manual-other-provider"), 4))
        assertFalse(fence.matches(1, key.copy(videoId = "wm_episode13", episodeNumber = 6), 4))
    }

    @Test fun lateRefreshAndFailedRefreshNeverPublishOrSubstitute() = runBlocking {
        var current = true
        assertNull(refreshCorePlayerRequest({ current }) { current = false; refreshed })
        assertNull(refreshCorePlayerRequest({ true }) { null })
        var requested = false
        assertNull(refreshCorePlayerRequest({ false }) { requested = true; refreshed })
        assertFalse(requested)
    }

    @Test fun cancelledNetworkCompletionNeverBecomesPlayerSource() = runBlocking {
        var published = false
        val job = launch {
            try {
                val stream = refreshCorePlayerRequest({ true }) { currentCoroutineContext().cancel(); refreshed }
                published = stream != null
                fail("Cancelled source request returned")
            } catch (_: CancellationException) { }
        }
        job.join()
        assertFalse(published)
    }

    @Test fun exactRecoveryReplacesCredentialsAndSubtitlesPreservingPausePositionAndEpisode() {
        for (playing in listOf(false, true)) {
            val runtime = PlayerScreenRuntime(args())
            runtime.resetIdentityStateIfNeeded()
            runtime.shouldPlay = playing
            runtime.installCoreRecovery(refreshed, 123_456)
            runtime.resetIdentityStateIfNeeded()
            assertEquals(playing, runtime.shouldPlay)
            runtime.resetIdentityStateIfNeeded()
            assertEquals(playing, runtime.shouldPlay)
            assertEquals(123_456L, runtime.activeInitialPositionMs)
            assertEquals("wm_episode12", runtime.activeVideoId)
            assertEquals(2, runtime.activeSeasonNumber)
            assertEquals(5, runtime.activeEpisodeNumber)
            assertEquals("wcore:jellyfin", runtime.activeProviderAddonId)
            assertEquals("Manual Jellyfin source", runtime.activeStreamTitle)
            assertEquals("wcore-source://1", runtime.activeCoreSelectionReference)
            assertEquals("Bearer fresh", runtime.activeSourceHeaders["Authorization"])
            assertEquals(refreshed.externalSubtitles, runtime.externalSubtitles)
            assertEquals(refreshed.url, runtime.activeSourceUrl)
        }
    }

    @Test fun identicalCredentialsRecoveryCreatesFreshEngineAndRejectsOldCallbacks() {
        val runtime = PlayerScreenRuntime(args())
        runtime.resetIdentityStateIfNeeded()
        runtime.shouldPlay = false
        val oldKey = runtime.activePlaybackKey
        val oldSeekKey = runtime.currentInitialPositionRequestKey()
        val sameCredentials = refreshed.copy(url = runtime.activeSourceUrl,
            behaviorHints = StreamBehaviorHints(proxyHeaders = StreamProxyHeaders(request = runtime.activeSourceHeaders)))
        runtime.installCoreRecovery(sameCredentials, runtime.activeInitialPositionMs)
        assertEquals(oldKey.sourceIdentity, runtime.activePlaybackKey.sourceIdentity)
        assertNotEquals(oldKey, runtime.activePlaybackKey)
        assertNotEquals(oldSeekKey, runtime.currentInitialPositionRequestKey())
        assertFalse(runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(isLoading = false, isPlaying = true), oldKey))
        runtime.resetIdentityStateIfNeeded()
        assertFalse(runtime.shouldPlay)
        assertFalse(runtime.initialSeekApplied)
        assertNull(runtime.pendingCorePlaybackReset)
    }

    @Test fun recoveryPausePreferenceCannotLeakIntoDifferentSourceOrEpisodeReset() {
        val runtime = PlayerScreenRuntime(args())
        runtime.resetIdentityStateIfNeeded()
        runtime.shouldPlay = false
        runtime.installCoreRecovery(refreshed, 123_456)
        runtime.activeVideoId = "wm_other123"
        runtime.activeEpisodeNumber = 6
        runtime.resetIdentityStateIfNeeded()
        assertTrue(runtime.shouldPlay)
        assertNull(runtime.pendingCorePlaybackReset)
    }

    @Test fun recoveryBudgetResetsOnlyAfterConfirmedPlaybackAdvance() {
        val runtime = PlayerScreenRuntime(args())
        runtime.coreRecoveryAttemptedReference = "wcore-source://1"
        runtime.installCoreRecovery(refreshed, 123_456)
        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(isLoading = false, isPlaying = false, positionMs = 125_000))
        assertNotNull(runtime.coreRecoveryAttemptedReference)
        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 123_456))
        assertNotNull(runtime.coreRecoveryAttemptedReference)
        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 125_000))
        assertNull(runtime.coreRecoveryAttemptedReference)
    }

    @Test fun corePickerCacheRejectsFailedOrEvictedHandlesWithoutRejectingAddonRows() {
        val core = AddonStreamGroup("W Core", "wcore", listOf(refreshed))
        assertTrue(isCorePlayerCacheReusable(listOf(core)) { it == "wcore-source://1" })
        assertFalse(isCorePlayerCacheReusable(listOf(core)) { false })
        assertFalse(isCorePlayerCacheReusable(listOf(core.copy(error = "Core unavailable"))) { true })
        val addon = AddonStreamGroup("Addon", "addon:regular", listOf(refreshed.copy(addonId = "addon:regular", coreSelectionReference = null)))
        assertTrue(isCorePlayerCacheReusable(listOf(addon)) { false })
    }

    @Test fun recoveryOnlyTriggersOnExpiryOrCredentialNetworkErrors() {
        val now = Instant.parse("2026-09-27T12:00:00Z")
        assertTrue(shouldRecoverWCorePlayerSource(now, "decoder failed", now))
        assertTrue(shouldRecoverWCorePlayerSource(null, "HTTP 403", now))
        assertTrue(shouldRecoverWCorePlayerSource(null, "ticket expired", now))
        assertFalse(shouldRecoverWCorePlayerSource(null, "decoder failed", now))
        assertFalse(shouldRecoverWCorePlayerSource(null, "codec 1403 not supported", now))
    }

    private fun args() = PlayerScreenArgs(
        profileId = 1, title = "Series", sourceUrl = "old-signed-url", sourceAudioUrl = null,
        sourceHeaders = mapOf("Authorization" to "Bearer old"), sourceResponseHeaders = emptyMap(),
        streamType = null, providerName = "W Core", streamTitle = "Manual Jellyfin source", streamSubtitle = null,
        initialBingeGroup = null, pauseDescription = null, onBack = {}, onOpenInExternalPlayer = null,
        onOpenExternalUrl = null, modifier = Modifier, logo = null, poster = null, background = null,
        seasonNumber = 2, episodeNumber = 5, episodeTitle = "Five", episodeThumbnail = null,
        contentType = "episode", videoId = "wm_episode12", parentMetaId = "wm_episode12", parentMetaType = "episode",
        providerAddonId = "wcore:jellyfin", torrentInfoHash = null, torrentFileIdx = null,
        torrentFilename = null, torrentTrackers = emptyList(), initialPositionMs = 123_456,
        initialProgressFraction = null, coreSelectionReference = "wcore-source://1",
    )
}
