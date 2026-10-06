package com.nuvio.app.features.livetv

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

internal data class LiveTvCatchupOwner(val account: String?, val profileId: Int, val backend: String, val signedOut: Boolean) {
    override fun toString() = "LiveTvCatchupOwner(captured)"
}

/** Process-only exact snapshots. Credentials never enter route arguments or persisted progress. */
internal class LiveTvCatchupSelection internal constructor(
    val owner: LiveTvCatchupOwner,
    internal val source: MobileLiveTvArchiveSource,
    internal val channel: LiveTvChannel,
    val archive: LiveTvArchiveCapability,
) {
    val channelId get() = channel.id
    val providerId get() = source.id
    val streamId get() = channel.id.removePrefix("${source.id}:")
    override fun toString() = "LiveTvCatchupSelection(captured)"
}

internal enum class LiveTvArchiveStatus { SUCCESS, NO_PROGRAMMES, UNSUPPORTED, UNKNOWN_RETENTION,
    UNKNOWN_TIMEZONE, INVALID_TIMEZONE, INVALID_TIME, EXPIRED, TOO_LONG, NOT_FINISHED,
    STALE_SELECTION, OUTSIDE_WINDOW, RESPONSE_TOO_LARGE, INVALID_RESPONSE, FETCH_FAILED }

internal enum class LiveTvArchiveFormat(val extension: String) { MPEG_TS("ts"), HLS("m3u8") }

/** Strict archive wall times reject DST overlaps and gaps; the device zone is never substituted. */
internal interface LiveTvArchiveClock {
    fun validZone(zone: String): Boolean
    fun timestamp(value: String, zone: String): Long?
    fun localTimestamp(epochMs: Long, zone: String): String
    fun archiveStart(epochMs: Long, zone: String): String
    fun dayBounds(day: String, zone: String): Pair<Long, Long>?
    fun days(nowMs: Long, retentionDays: Int, zone: String): List<String>
}

internal expect object LiveTvArchivePlatform {
    val clock: LiveTvArchiveClock?
    fun client(): io.ktor.client.HttpClient
}

internal fun liveTvArchiveEligibility(archive: LiveTvArchiveCapability?, startMs: Long, endMs: Long, nowMs: Long): LiveTvArchiveStatus {
    val days = archive?.retentionDays ?: return if (archive == null) LiveTvArchiveStatus.UNSUPPORTED else LiveTvArchiveStatus.UNKNOWN_RETENTION
    if (nowMs !in 0..253_402_300_799_999L || startMs < 0 || endMs <= startMs) return LiveTvArchiveStatus.INVALID_TIME
    if (endMs > nowMs) return LiveTvArchiveStatus.NOT_FINISHED
    val alignedStart = startMs / 60_000 * 60_000
    if (endMs - alignedStart > 43_200_000L) return LiveTvArchiveStatus.TOO_LONG
    if (nowMs - alignedStart > days.coerceAtMost(30) * 86_400_000L) return LiveTvArchiveStatus.EXPIRED
    return LiveTvArchiveStatus.SUCCESS
}

internal data class LiveTvArchiveProgramme internal constructor(val channelId: String, val title: String,
    val startMs: Long, val endMs: Long, val recordingAdvertised: Boolean) {
    override fun toString() = "LiveTvArchiveProgramme(captured)"
}

internal class LiveTvArchiveHistory internal constructor(val selection: LiveTvCatchupSelection,
    val zone: String, val day: String, val status: LiveTvArchiveStatus,
    val programmes: List<LiveTvArchiveProgramme> = emptyList(), val airing: LiveTvArchiveProgramme? = null) {
    override fun toString() = "LiveTvArchiveHistory(captured)"
}

internal class LiveTvCatchupRequest private constructor(val selection: LiveTvCatchupSelection,
    val programme: LiveTvArchiveProgramme, val zone: String, val recordedThroughMs: Long) {
    val startMs get() = programme.startMs
    val endMs get() = recordedThroughMs
    val partial get() = recordedThroughMs < programme.endMs
    override fun toString() = "LiveTvCatchupRequest(captured)"
    companion object {
        fun fromHistory(history: LiveTvArchiveHistory, programme: LiveTvArchiveProgramme, nowMs: Long,
            watchFromStart: Boolean = false): LiveTvCatchupRequest? {
            if (history.status != LiveTvArchiveStatus.SUCCESS || programme.channelId != history.selection.channelId) return null
            val end = if (watchFromStart) {
                if (programme != history.airing || !programme.recordingAdvertised || programme.startMs >= nowMs || programme.endMs <= nowMs) return null
                nowMs / 60_000 * 60_000
            } else {
                if (programme !in history.programmes) return null
                programme.endMs
            }
            if (liveTvArchiveEligibility(history.selection.archive, programme.startMs, end, nowMs) != LiveTvArchiveStatus.SUCCESS) return null
            return LiveTvCatchupRequest(history.selection, programme, history.zone, end)
        }
    }
}

internal fun liveTvXtreamArchiveBase(source: MobileLiveTvArchiveSource): String? {
    val endpoint = source.serverUrl.trim().trimEnd('/')
    if (endpoint.length > 8192 || '?' in endpoint || '#' in endpoint || endpoint.any { it.code < 32 || it.code == 127 }) return null
    val url = runCatching { Url(endpoint) }.getOrNull() ?: return null
    val authority = endpoint.substringAfter("://", "").substringBefore('/')
    if (url.protocol.name !in listOf("http", "https") || url.host.isBlank() || url.port !in 1..65535 ||
        '@' in authority || url.parameters.entries().isNotEmpty() || url.fragment.isNotBlank()) return null
    val path = url.encodedPath.removeSuffix("/player_api.php").trimEnd('/')
    if ('%' in path || '\\' in path || path.split('/').any { it == "." || it == ".." }) return null
    if (listOf(source.username, source.password).any { it.isBlank() || it.length > 1024 || it.any { c -> c.code < 32 || c.code == 127 } }) return null
    return "${url.protocol.name}://$authority$path"
}

internal fun liveTvXtreamArchiveInfoUrl(selection: LiveTvCatchupSelection): String? {
    val base = liveTvXtreamArchiveBase(selection.source) ?: return null
    return "$base/player_api.php?username=${selection.source.username.encodeURLParameter()}&password=${selection.source.password.encodeURLParameter()}"
}

internal fun liveTvXtreamHistoryUrl(selection: LiveTvCatchupSelection): String? = liveTvXtreamArchiveInfoUrl(selection)?.let {
    "$it&action=get_simple_data_table&stream_id=${selection.streamId.encodeURLParameter()}"
}

/** Revalidate owner/config/raw row immediately before playback; never substitute a title-matched source. */
internal fun resolveLiveTvXtreamCatchupUrl(request: LiveTvCatchupRequest, clock: LiveTvArchiveClock, nowMs: Long,
    format: LiveTvArchiveFormat = LiveTvArchiveFormat.MPEG_TS): String? {
    if (!LiveTvRepository.ownsCatchup(request.selection) || !clock.validZone(request.zone) ||
        liveTvArchiveEligibility(request.selection.archive, request.startMs, request.endMs, nowMs) != LiveTvArchiveStatus.SUCCESS) return null
    val source = request.selection.source
    val base = liveTvXtreamArchiveBase(source) ?: return null
    val minutes = (request.endMs - request.startMs / 60_000 * 60_000 + 59_999) / 60_000
    val archiveEnd = request.startMs / 60_000 * 60_000 + minutes * 60_000
    if (archiveEnd > nowMs / 60_000 * 60_000) return null
    fun segment(value: String) = value.encodeURLParameter().replace("+", "%20")
    val start = runCatching { clock.archiveStart(request.startMs, request.zone) }.getOrNull() ?: return null
    return "$base/timeshift/${segment(source.username)}/${segment(source.password)}/$minutes/" +
        "$start/${request.selection.streamId}.${format.extension}"
}

