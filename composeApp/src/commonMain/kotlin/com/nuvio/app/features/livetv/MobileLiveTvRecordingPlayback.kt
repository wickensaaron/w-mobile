package com.nuvio.app.features.livetv

import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Playback URLs stay in memory, never in navigation arguments. */
internal object LiveTvRecordingPlaybackRequests {
    private val pending = MutableSharedFlow<PlayerLaunch>(extraBufferCapacity = 1)
    val requests = pending.asSharedFlow()
    fun request(recording: LiveTvRecording, url: String, profileId: Int = ProfileRepository.activeProfileId) {
        val launch = runCatching { createRecordingPlayerLaunch(recording, url, profileId) }.getOrNull() ?: return
        pending.tryEmit(launch)
    }
}
