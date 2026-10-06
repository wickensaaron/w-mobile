package com.nuvio.app.features.livetv

import com.nuvio.app.features.player.PlayerLaunch

/** Credentials/configuration are hashed, never saved as history metadata or route arguments. */
internal fun liveTvCatchupFingerprint(selection: LiveTvCatchupSelection): String {
    val source = selection.source
    val channel = selection.channel
    val parts = listOf(selection.owner.account.orEmpty(), selection.owner.profileId.toString(), selection.owner.backend,
        selection.owner.signedOut.toString(), source.syncId, source.name, source.serverUrl, source.username, source.password,
        source.epgUrl, channel.name, channel.streamUrl, channel.guideId.orEmpty(), channel.streamType.orEmpty(), selection.archive.toString())
    return com.nuvio.app.features.profiles.ProfilePinCrypto.sha256Hex("catchup\u0000" + parts.joinToString("") { "${it.length}:$it" })
}

internal const val LiveTvCatchupHistoryPrefix = "wcatchup:"
internal data class LiveTvCatchupHistoryIdentity(val sourceId: String, val streamId: String,
    val startMs: Long, val endMs: Long, val fingerprint: String) {
    override fun toString() = "LiveTvCatchupHistoryIdentity(captured)"
}

internal fun liveTvCatchupHistoryId(request: LiveTvCatchupRequest): String? {
    // A partial recording is not a finished episode and must not populate Next Up/Continue Watching.
    if (request.partial) return null
    val selection = request.selection
    if (!Regex("[A-Za-z0-9_-]{1,100}").matches(selection.source.syncId)) return null
    return "$LiveTvCatchupHistoryPrefix${selection.source.syncId}:${selection.streamId}:${request.startMs}:${request.endMs}:${liveTvCatchupFingerprint(selection)}"
}

internal fun parseLiveTvCatchupHistoryId(value: String): LiveTvCatchupHistoryIdentity? {
    if (!value.startsWith(LiveTvCatchupHistoryPrefix) || value.length > 300) return null
    val parts = value.removePrefix(LiveTvCatchupHistoryPrefix).split(':')
    if (parts.size != 5 || !Regex("[A-Za-z0-9_-]{1,100}").matches(parts[0]) ||
        !Regex("[A-Za-z0-9_-]{1,80}").matches(parts[1]) || !Regex("[a-f0-9]{64}").matches(parts[4])) return null
    val start = parts[2].toLongOrNull()?.takeIf { it in 0..253_402_300_799_999L } ?: return null
    val end = parts[3].toLongOrNull()?.takeIf { it in (start + 1)..253_402_300_799_999L && it - start <= 43_200_000L } ?: return null
    return LiveTvCatchupHistoryIdentity(parts[0], parts[1], start, end, parts[4])
}

internal class LiveTvCatchupPlayback(val request: LiveTvCatchupRequest, val sourceUrl: String, val historyId: String?) {
    val title get() = request.programme.title
    val channelName get() = request.selection.channel.name
    val logo get() = request.selection.channel.logoUrl
    override fun toString() = "LiveTvCatchupPlayback(captured)"
}

internal fun buildLiveTvCatchupPlayerLaunch(profileId: Int, playback: LiveTvCatchupPlayback,
    resumePositionMs: Long = 0): PlayerLaunch {
    val request = playback.request
    val identity = playback.historyId ?: "wcatchup-partial:${request.selection.source.syncId}:${request.startMs}"
    return PlayerLaunch(
        profileId = profileId, title = playback.title, sourceUrl = playback.sourceUrl,
        streamType = "ts", logo = playback.logo, poster = playback.logo,
        streamTitle = playback.title, streamSubtitle = playback.channelName,
        providerName = "Catch-up", providerAddonId = "live-tv-catchup",
        contentType = if (request.partial) "catchup-partial" else "catchup",
        videoId = identity, parentMetaId = identity, parentMetaType = "catchup",
        initialPositionMs = resumePositionMs.coerceAtLeast(0),
        pauseDescription = if (request.partial) "Recorded so far · return to the guide for Live TV" else "Recorded programme · ${playback.channelName}",
    )
}

/** Only a numeric PlayerRoute reference leaves the in-memory launch boundary. */
internal object LiveTvCatchupPlaybackRegistry {
    private val requests = mutableMapOf<Long, LiveTvCatchupRequest>()
    fun register(launchId: Long, request: LiveTvCatchupRequest) { requests[launchId] = request }
    fun get(launchId: Long): LiveTvCatchupRequest? = requests[launchId]
    fun remove(launchId: Long) { requests.remove(launchId) }
    fun clear() { requests.clear() }
}
