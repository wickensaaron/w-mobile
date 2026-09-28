package com.nuvio.app.features.watchprogress

/**
 * Synced Live TV and Windows archive identities require a source-owned mobile resolver.
 * The current mobile catalogue has no source binding for imported progress, so these rows
 * stay in durable history/sync but must not enter generic addon presentation or playback.
 * Do not infer Live TV ownership from a numeric/UUID identity or a channel name.
 */
internal fun isUnsupportedMobileLiveTvIdentity(
    type: String,
    contentId: String,
    videoId: String = contentId,
): Boolean = when (type.trim().lowercase()) {
    "live", "livetv", "live-tv", "live_tv",
    "catchup", "catchup-partial", "live-tv-replay", "live-tv-catchup" -> true
    else -> contentId.isWindowsLiveTvArchiveIdentity() || videoId.isWindowsLiveTvArchiveIdentity()
}

internal fun WatchProgressEntry.isUnsupportedMobileLiveTvProgress(): Boolean =
    isUnsupportedMobileLiveTvIdentity(contentType, parentMetaId, videoId) ||
        isUnsupportedMobileLiveTvIdentity(parentMetaType, parentMetaId, videoId) ||
        providerAddonId.equals("live-tv", ignoreCase = true) ||
        providerAddonId.equals("live-tv-catchup", ignoreCase = true)

internal fun ContinueWatchingItem.isUnsupportedMobileLiveTvProgress(): Boolean =
    isUnsupportedMobileLiveTvIdentity(parentMetaType, parentMetaId, videoId)

private fun String.isWindowsLiveTvArchiveIdentity(): Boolean =
    startsWith("wcatchup:") || startsWith("wcatchup-partial:")
