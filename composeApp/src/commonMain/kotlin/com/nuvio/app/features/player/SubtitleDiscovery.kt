package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaDetails

private val subtitleImdbId = Regex("tt[0-9]+")
private val subtitleImdbEpisodeId = Regex("tt[0-9]+:[0-9]+:[0-9]+")

/** Subtitle add-ons need the public movie/episode identity, not the library's playback ID. */
internal fun subtitleDiscoveryVideoId(
    videoId: String?,
    type: String,
    parentId: String,
    metadata: MetaDetails?,
    season: Int?,
    episode: Int?,
): String? {
    val current = videoId?.trim()?.takeIf(String::isNotBlank)
    val series = type.equals("series", true) || type.equals("tv", true)
    if (current != null && (if (series) subtitleImdbEpisodeId else subtitleImdbId).matches(current)) return current
    // Metadata can arrive after playback starts or still refer to the previous item.
    val ownedMetadata = metadata?.takeIf { it.id == parentId &&
        (it.type.equals(type, true) || series && it.type.equals("series", true)) }
    val imdb = ownedMetadata?.imdbId?.takeIf(subtitleImdbId::matches)
        ?: parentId.takeIf(subtitleImdbId::matches)
        ?: return current
    if (!series) return if (type.equals("movie", true)) imdb else current
    // Never search a whole series or guess an episode. Season zero is valid for specials.
    if (season == null || season < 0 || episode == null || episode < 1)
        return current?.takeUnless(subtitleImdbId::matches)
    return "$imdb:$season:$episode"
}
