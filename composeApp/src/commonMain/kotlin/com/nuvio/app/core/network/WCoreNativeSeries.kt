package com.nuvio.app.core.network

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal const val NativeSeriesPageSize = 200
internal const val NativeSeriesMaximumItems = 10_000

/** Verified membership only: no response URLs, request headers, tokens, or image tickets. */
internal data class WCoreSeriesMember(val id: String, val season: Int, val episode: Int, val title: String)
internal data class WCoreSeriesMembership(
    val id: String, val jellyfinSeriesId: String, val title: String, val members: List<WCoreSeriesMember>,
)

/** Start at zero and publish only a distinct, contiguous, complete snapshot. */
internal suspend fun loadWCoreSeriesMembership(
    seriesId: String, request: suspend (String?) -> String,
): WCoreSeriesMembership {
    require(isWCoreMediaId(seriesId))
    var series: Triple<String, String, String>? = null
    var snapshot: String? = null
    var total: Int? = null
    var cursor: String? = null
    val cursors = mutableSetOf<String>()
    val ids = mutableSetOf<String>()
    val coordinates = mutableSetOf<Pair<Int, Int>>()
    val accumulated = mutableListOf<WCoreSeriesMember>()
    repeat(NativeSeriesMaximumItems / NativeSeriesPageSize + 1) {
        currentCoroutineContext().ensureActive()
        val body = request(cursor)
        currentCoroutineContext().ensureActive()
        val page = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid Core series page")
        val identity = page["series"] as? JsonObject ?: error("Missing Core series identity")
        val pageSeriesId = identity.strictText("wMediaId")
        val jellyfinId = identity.strictText("jellyfinSeriesId")
        val title = identity.strictText("title")
        check(pageSeriesId == seriesId && jellyfinId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) && title.length in 1..300)
        val pageSeries = Triple(pageSeriesId, jellyfinId, title)
        check(series == null || series == pageSeries) { "Core series changed between pages" }
        series = pageSeries
        val pagination = page["pagination"] as? JsonObject ?: error("Missing Core series pagination")
        val pageSnapshot = pagination.strictText("snapshotId")
        check(pageSnapshot.matches(Regex("[A-Za-z0-9_-]{16,128}")))
        check(snapshot == null || snapshot == pageSnapshot) { "Core series snapshot changed" }
        snapshot = pageSnapshot
        val pageTotal = pagination.strictInt("totalItems") ?: error("Invalid Core series total")
        check(pageTotal in 0..NativeSeriesMaximumItems && (total == null || total == pageTotal))
        total = pageTotal
        val offset = pagination.strictInt("offset") ?: error("Invalid Core series offset")
        check(offset == accumulated.size) { "Core series pages are not contiguous" }
        check(pagination["truncated"] == JsonPrimitive(false)) { "Core series is truncated" }
        val complete = (pagination["complete"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: error("Invalid Core series completion")
        check(pagination.containsKey("nextCursor"))
        val next = if (pagination["nextCursor"] == JsonNull) null else pagination.strictText("nextCursor").also {
            check(it.matches(Regex("[A-Za-z0-9_-]{16,256}")) && cursors.add(it)) { "Invalid Core series cursor" }
        }
        val values = page["items"] as? JsonArray ?: error("Missing Core series members")
        check(values.size <= NativeSeriesPageSize && accumulated.size + values.size <= pageTotal)
        for (value in values) {
            val item = value as? JsonObject ?: error("Invalid Core series member")
            val id = item.strictText("wMediaId")
            check(isWCoreMediaId(id) && id != seriesId && item.strictText("seriesWMediaId") == seriesId && ids.add(id))
            val season = item.strictInt("season") ?: error("Invalid Core episode season")
            val episode = item.strictInt("episode") ?: error("Invalid Core episode number")
            check(season >= 0 && episode > 0 && coordinates.add(season to episode))
            val episodeTitle = item.strictText("title")
            check(episodeTitle.length in 1..300)
            val availability = item["availability"] as? JsonObject ?: error("Missing Core episode availability")
            check(availability["local"] == JsonPrimitive(true) && availability["playable"] == JsonPrimitive(true))
            accumulated.lastOrNull()?.let { previous ->
                check(previous.season < season || (previous.season == season && previous.episode < episode)) { "Core series ordering changed" }
            }
            accumulated += WCoreSeriesMember(id, season, episode, episodeTitle)
        }
        if (complete) {
            check(next == null && accumulated.size == pageTotal) { "Incomplete Core series snapshot" }
            return WCoreSeriesMembership(seriesId, jellyfinId, title, accumulated.sortedWith(compareBy({ it.season }, { it.episode })))
        }
        check(next != null && values.isNotEmpty() && accumulated.size < pageTotal)
        cursor = next
    }
    error("Core series page bound exceeded")
}

internal fun nativeCoreSeriesDetails(
    membership: WCoreSeriesMembership, focusedEpisodeId: String? = null, seed: MetaDetails? = null,
): MetaDetails = MetaDetails(
    id = membership.id, type = "series", name = membership.title,
    poster = seed?.poster, background = seed?.background, logo = seed?.logo,
    description = seed?.description, releaseInfo = seed?.releaseInfo,
    defaultVideoId = focusedEpisodeId?.takeIf { focused -> membership.members.any { it.id == focused } },
    videos = membership.members.map { MetaVideo(id = it.id, title = it.title, season = it.season, episode = it.episode, available = true) },
)

private fun JsonObject.strictText(key: String): String = (get(key) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: error("Invalid Core series field")


private fun JsonObject.strictInt(key: String): Int? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

internal fun verifiedSeriesContainsEpisode(membership: WCoreSeriesMembership, episode: WCoreLibraryItem): Boolean =
    episode.type == "episode" && episode.seriesMembershipStatus == "verified" && episode.seriesWMediaId == membership.id &&
        episode.jellyfinSeriesId == membership.jellyfinSeriesId && membership.members.any {
            it.id == episode.id && it.season == episode.season && it.episode == episode.episode
        }
