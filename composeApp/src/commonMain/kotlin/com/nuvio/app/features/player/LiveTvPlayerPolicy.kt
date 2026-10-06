package com.nuvio.app.features.player

internal fun isManagedLiveTvPlayback(providerId: String?): Boolean =
    providerId == "live-tv-catchup" || providerId == "live-tv-recording"

internal fun recordingStartSkipTargetMs(offsetMs: Long?, positionMs: Long, durationMs: Long): Long? =
    offsetMs?.takeIf { it > 0L && durationMs > it && positionMs in 0 until it }
