package com.nuvio.app.features.livetv

/** Advertised archive support. Missing retention stays unknown and never enables replay. */
data class LiveTvArchiveCapability(val retentionDays: Int?) {
    init { require(retentionDays == null || retentionDays > 0) }
}

internal fun parseLiveTvXtreamArchiveCapability(flag: String?, days: String?): LiveTvArchiveCapability? {
    if (flag?.trim() != "1") return null
    return LiveTvArchiveCapability(days?.trim()?.toIntOrNull()?.takeIf { it > 0 })
}

internal enum class MobileLiveTvReplayEligibility {
    ELIGIBLE, NOT_ADVERTISED, UNKNOWN_RETENTION, INVALID_TIME, NOT_FINISHED, EXPIRED, TOO_LONG,
}

/** Never offer an archive action from guide data alone; the provider must advertise usable retention. */
internal fun mobileLiveTvReplayEligibility(channel: LiveTvChannel, programme: LiveTvProgramme,
    nowMs: Long): MobileLiveTvReplayEligibility {
    val archive = channel.archive ?: return MobileLiveTvReplayEligibility.NOT_ADVERTISED
    val days = archive.retentionDays ?: return MobileLiveTvReplayEligibility.UNKNOWN_RETENTION
    val maxTime = 253_402_300_799_999L
    if (programme.channelId != channel.id || nowMs !in 0..maxTime ||
        programme.startEpochMs !in 0..maxTime || programme.stopEpochMs !in 0..maxTime ||
        programme.stopEpochMs <= programme.startEpochMs) return MobileLiveTvReplayEligibility.INVALID_TIME
    if (programme.stopEpochMs > nowMs) return MobileLiveTvReplayEligibility.NOT_FINISHED
    val alignedStart = programme.startEpochMs / 60_000L * 60_000L
    if (programme.stopEpochMs - alignedStart > 12L * 60 * 60 * 1000)
        return MobileLiveTvReplayEligibility.TOO_LONG
    if (nowMs - alignedStart > days.coerceAtMost(30) * 86_400_000L)
        return MobileLiveTvReplayEligibility.EXPIRED
    return MobileLiveTvReplayEligibility.ELIGIBLE
}
