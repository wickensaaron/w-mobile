package com.nuvio.app.features.livetv

import com.nuvio.app.features.player.PlayerLaunch

// Bound to each private URL, never to a recording ID alone: a later owner cannot replace an old launch's guard.
private val recordingPlaybackOwners = mutableMapOf<String, Pair<String, RecordingRequestOwner>>()

internal fun rememberIssuedRecordingPlaybackUrl(recordingId: String, url: String, owner: RecordingRequestOwner) {
    if (!owner.isCurrent()) return
    if (recordingPlaybackOwners.size >= 32) recordingPlaybackOwners.remove(recordingPlaybackOwners.keys.first())
    recordingPlaybackOwners[url] = recordingId to owner
}

internal fun createRecordingPlayerLaunch(recording: LiveTvRecording, url: String, profileId: Int): PlayerLaunch {
    val issued = recordingPlaybackOwners[url]
    check(issued != null && issued.first == recording.id && issued.second.profileId == profileId && issued.second.isCurrent()) {
        "Your recording account changed. Try again."
    }
    return PlayerLaunch(
        profileId = profileId, title = recording.title, sourceUrl = url,
        streamType = "video/mp2t", streamTitle = recording.title,
        streamSubtitle = recording.channelName, providerName = "W Core recordings",
        providerAddonId = "live-tv-recording", contentType = "recording",
        videoId = "recording:${recording.id}", parentMetaId = "recording:${recording.id}", parentMetaType = "recording",
        poster = recording.artworkUrl,
        recordingProgrammeStartOffsetMs = recordingProgrammeStartOffsetMs(recording),
    )
}

internal fun isCurrentRecordingPlaybackRequest(launch: PlayerLaunch): Boolean =
    launch.providerAddonId == "live-tv-recording" && recordingPlaybackOwners[launch.sourceUrl]?.let { (id, owner) ->
        launch.parentMetaId == "recording:$id" && owner.profileId == launch.profileId && owner.isCurrent()
    } == true

internal suspend fun resumeRecordingPlayerLaunch(recordingId: String, profileId: Int, positionMs: Long): PlayerLaunch? {
    val owner = RecordingRequestOwner(profileId)
    val id = recordingId.removePrefix("recording:")
    val recording = LiveTvRecordingClient.list(profileId).firstOrNull { it.id == id && it.status == "ready" } ?: return null
    val url = LiveTvRecordingClient.playbackUrl(profileId, id)
    if (!owner.isCurrent()) return null
    return createRecordingPlayerLaunch(recording, url, profileId).copy(initialPositionMs = positionMs.coerceAtLeast(0))
}
