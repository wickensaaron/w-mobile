package com.nuvio.app.features.livetv

/** A saved raw alternative only stars the visible broadcast while its own guide remains usable. */
internal fun isLiveTvGuideRowFavourite(row: LiveTvAccountGuideRow, favourites: Set<String>,
    programmes: Map<String, List<LiveTvProgramme>>, nowMs: Long): Boolean =
    (row.alternatives + row.channel).any { channel ->
        channel.id in favourites && channel.accountScope == row.channel.accountScope &&
            channel.accountSourceGeneration == row.channel.accountSourceGeneration &&
            programmes[channel.id].orEmpty().any {
                it.channelId == channel.id && it.title.isNotBlank() && it.stopEpochMs > nowMs &&
                    it.stopEpochMs > it.startEpochMs && it.startEpochMs < nowMs + 24L * 60 * 60 * 1000
            }
    }

/** Preserve newer category/order/tidy choices when retrying one explicit favourite intent. */
internal fun applyLiveTvAccountFavouriteIntent(snapshot: LiveTvAccountGuideSnapshot, channelId: String,
    alternativeIds: Set<String>, shouldAdd: Boolean): LiveTvAccountGuidePreferences {
    require(canonicalAccountGuideProvider(channelId) != null && channelId in alternativeIds)
    val preferences = snapshot.preferences ?: LiveTvAccountGuidePreferences()
    val favourites = preferences.favouriteIds.filter { it !in alternativeIds }
        .let { if (shouldAdd) it + channelId else it }
    return preferences.copy(favouriteIds = favourites.distinct()).also(::validateLiveTvAccountGuidePreferences)
}
