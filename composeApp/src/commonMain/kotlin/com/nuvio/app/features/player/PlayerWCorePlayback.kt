package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.WCorePlaybackSources
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Captures the player request, independently of short-lived playback credentials. */
internal data class CorePlayerRequestFence(val profileId: Int, val playbackKey: PlaybackKey, val generation: Long) {
    fun matches(currentProfileId: Int, currentPlaybackKey: PlaybackKey, currentGeneration: Long): Boolean =
        profileId == currentProfileId && playbackKey == currentPlaybackKey && generation == currentGeneration
}

internal fun PlayerScreenRuntime.setCoreSelection(reference: String?) {
    if (activeCoreSelectionReference == reference) return
    WCorePlaybackSources.releaseSelection(activeCoreSelectionReference)
    activeCoreSelectionReference = reference
    WCorePlaybackSources.pinSelection(reference)
    coreRecoveryAttemptedReference = null
    coreRecoveryResumePositionMs = null
}

/** Source-picker and episode-picker choices stay exact, including after a late server response. */
internal fun PlayerScreenRuntime.resolveCoreForPlayer(stream: StreamItem, requestCurrent: () -> Boolean = { true }, onResolved: (StreamItem) -> Unit): Boolean {
    coreSourceRequestGeneration++
    coreSourceSwitchJob?.cancel()
    credentialRefreshJob?.cancel()
    if (!stream.isWCoreStream) return false
    val reference = stream.coreSelectionReference ?: stream.url
    if (reference == null || !WCorePlaybackSources.isSelectionCurrent(reference)) {
        NuvioToastController.show("W Core source unavailable. Retry its connection in Settings.")
        return true
    }
    val fence = CorePlayerRequestFence(profileId, activePlaybackKey, coreSourceRequestGeneration)
    val sourcePanelWasVisible = showSourcesPanel
    val episodesPanelWasVisible = showEpisodesPanel
    val selectedEpisodeId = episodeStreamsPanelState.selectedEpisode?.id
    fun isCurrent(): Boolean = requestCurrent() && fence.matches(ProfileRepository.activeProfileId, activePlaybackKey, coreSourceRequestGeneration) &&
        WCorePlaybackSources.isSelectionCurrent(reference) &&
        (!sourcePanelWasVisible || showSourcesPanel) &&
        (!episodesPanelWasVisible || (showEpisodesPanel && episodeStreamsPanelState.selectedEpisode?.id == selectedEpisodeId))
    coreSourceSwitchJob = scope.launch {
        val refreshed = refreshCorePlayerRequest(::isCurrent) { WCorePlaybackSources.refreshSelected(stream) }
        if (!isCurrent()) return@launch
        if (refreshed != null) {
            coreRecoveryAttemptedReference = null
            onResolved(refreshed)
        } else NuvioToastController.show("W Core source unavailable. Retry its connection in Settings.")
    }
    return true
}

/** Core never enters the addon provider/title-scoring recovery path. */
internal fun PlayerScreenRuntime.tryRefreshCoreSourceAfterError(message: String?): Boolean {
    val reference = activeCoreSelectionReference ?: return false
    if (!WCorePlaybackSources.isSelectionCurrent(reference) || !WCorePlaybackSources.shouldRecover(reference, message)) return false
    if (credentialRefreshJob?.isActive == true) return true
    if (coreRecoveryAttemptedReference == reference) return false
    coreRecoveryAttemptedReference = reference
    coreSourceRequestGeneration++
    coreSourceSwitchJob?.cancel()
    val fence = CorePlayerRequestFence(profileId, activePlaybackKey, coreSourceRequestGeneration)
    val savedPosition = playbackSnapshot.positionMs.coerceAtLeast(0L)
    errorMessage = null
    controlsVisible = !playerControlsLocked
    fun isCurrent() = fence.matches(ProfileRepository.activeProfileId, activePlaybackKey, coreSourceRequestGeneration) &&
        activeCoreSelectionReference == reference && WCorePlaybackSources.isSelectionCurrent(reference)
    credentialRefreshJob = scope.launch {
        val refreshed = refreshCorePlayerRequest(::isCurrent) { WCorePlaybackSources.refreshReference(reference) }
        if (!isCurrent()) return@launch
        if (refreshed == null) {
            errorMessage = message
            controlsVisible = !playerControlsLocked
            return@launch
        }
        if (requireEnglishAudio && !com.nuvio.app.features.streams.verifyBalancedAutoPlay(refreshed)) {
            if (isCurrent()) errorMessage = com.nuvio.app.features.streams.BalancedAutoPlayPolicy.FALLBACK_MESSAGE
            return@launch
        }
        if (!isCurrent()) return@launch
        // Keep the current play/pause preference; only the exact source credentials change.
        flushWatchProgress()
        installCoreRecovery(refreshed, savedPosition)
    }
    return true
}


internal suspend fun refreshCorePlayerRequest(isCurrent: () -> Boolean, refresh: suspend () -> StreamItem?): StreamItem? {
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return null
    val result = refresh()
    currentCoroutineContext().ensureActive()
    return result.takeIf { isCurrent() }
}


/** Apply only ephemeral credentials, preserving playback content and the current pause preference. */
internal fun PlayerScreenRuntime.installCoreRecovery(refreshed: StreamItem, savedPosition: Long) {
    activeSourceUrl = refreshed.playableDirectUrl ?: return
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(refreshed.behaviorHints.proxyHeaders?.request)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(refreshed.behaviorHints.proxyHeaders?.response)
    externalSubtitles = refreshed.externalSubtitles
    activeStreamType = refreshed.streamType
    activeSourceIdentityKey = refreshed.playerSourceIdentityKey()
    activeInitialPositionMs = savedPosition
    coreRecoveryResumePositionMs = savedPosition
    activeInitialProgressFraction = null
    scheduleCorePlaybackReset(shouldPlay)
    errorMessage = null
}


internal data class CorePlaybackResetPreference(val playbackKey: PlaybackKey, val shouldPlay: Boolean)

/** Force a fresh engine even when the exact source returns identical URL/headers. */
internal fun PlayerScreenRuntime.scheduleCorePlaybackReset(playWhenReady: Boolean) {
    corePlaybackReloadGeneration++
    pendingCorePlaybackReset = CorePlaybackResetPreference(activePlaybackKey, playWhenReady)
}


/** An early episode fallback can join a parent only through exact verified membership. */
internal fun nativeSeriesMatchesPlayback(
    meta: com.nuvio.app.features.details.MetaDetails, episodeId: String, season: Int?, episode: Int?,
): Boolean = meta.type == "series" && com.nuvio.app.core.network.isWCoreMediaId(meta.id) &&
    com.nuvio.app.core.network.isWCoreMediaId(episodeId) && season != null && season >= 0 && episode != null && episode > 0 &&
    meta.videos.any { it.id == episodeId && it.season == season && it.episode == episode && it.available }
