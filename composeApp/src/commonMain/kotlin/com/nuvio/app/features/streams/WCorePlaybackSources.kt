package com.nuvio.app.features.streams

import com.nuvio.app.core.network.WCoreConnectionRepository
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.details.MetaDetailsRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal const val W_CORE_ADDON_ID = "wcore"

internal data class PreparedWCorePlaybackRequest(
    val origin: String,
    val accessToken: String,
    val body: String,
)

/** Adds W Core's Jellyfin and approved remote sources to Nuvio's existing source picker. */
internal object WCorePlaybackSources {
    fun prepare(
        type: String,
        videoId: String,
        parentMetaId: String?,
        season: Int?,
        episode: Int?,
        preferredAudioLanguage: String?,
    ): PreparedWCorePlaybackRequest? {
        val origin = WCoreConnectionRepository.currentOrigin() ?: return null
        val token = WCoreConnectionRepository.currentAccessToken() ?: return null
        val mediaType = when {
            season != null && episode != null && season >= 0 && episode > 0 -> "episode"
            type.equals("movie", true) || type.equals("film", true) -> "movie"
            else -> return null
        }
        val metaId = if (mediaType == "episode") {
            parentMetaId ?: if (videoId.startsWith("tmdb:", true)) {
                videoId.split(':').take(2).joinToString(":")
            } else videoId.substringBefore(':')
        } else videoId
        val meta = MetaDetailsRepository.peek(if (mediaType == "episode") "series" else type, metaId)
        val imdb = sequenceOf(meta?.imdbId, meta?.id, metaId, videoId)
            .filterNotNull()
            .mapNotNull { Regex("tt[0-9]{7,10}", RegexOption.IGNORE_CASE).find(it)?.value?.lowercase() }
            .firstOrNull()
        val tmdb = sequenceOf(meta?.id, metaId, videoId)
            .filterNotNull()
            .mapNotNull { Regex("(?:^|:)tmdb:(?:movie:|series:|tv:)?([0-9]+)", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.getOrNull(1) }
            .firstOrNull()
        if (imdb == null && tmdb == null) return null
        val identity = buildJsonObject {
            put("mediaType", mediaType)
            if (meta?.name?.isNotBlank() == true) put("title", meta.name)
            if (mediaType == "episode") {
                put("season", season!!)
                put("episode", episode!!)
            }
            put("externalIds", buildJsonObject {
                if (imdb != null) put("imdb", imdb)
                if (tmdb != null) put("tmdb", tmdb)
            })
            preferredAudioLanguage?.trim()?.takeIf { it.length in 2..3 && it != "device" }?.let {
                put("preferredAudioLanguage", it.lowercase())
            }
        }
        return PreparedWCorePlaybackRequest(origin, token, identity.toString())
    }

    suspend fun load(request: PreparedWCorePlaybackRequest): AddonStreamGroup {
        val response = httpRequestRaw(
            method = "POST",
            url = "${request.origin}/api/v1/playback/resolve",
            headers = mapOf(
                "Authorization" to "Bearer ${request.accessToken}",
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            ),
            body = request.body,
            followRedirects = false,
            maxResponseBodyBytes = 64 * 1024,
        )
        if (response.status == 401 || response.status == 403) {
            WCoreConnectionRepository.retry()
        }
        return AddonStreamGroup(
            addonName = "W Core",
            addonId = W_CORE_ADDON_ID,
            streams = if (response.status in 200..299) parseWCorePlaybackSources(
                response.body, request.origin, request.accessToken,
            ) else emptyList(),
            error = when (response.status) {
                in 200..299, 404 -> null
                401, 403 -> "W Core sign-in expired"
                else -> "W Core sources unavailable"
            },
        )
    }
}

internal fun parseWCorePlaybackSources(body: String, origin: String, accessToken: String): List<StreamItem> {
    val response = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
    val sources = (response["sources"] as? JsonArray)?.toList()?.takeIf { it.isNotEmpty() }
        ?: listOf(response)
    return sources.mapNotNull { parseWCorePlaybackSource(it, origin, accessToken) }
        .distinctBy { it.sourceName }
}

private fun parseWCorePlaybackSource(value: JsonElement, origin: String, accessToken: String): StreamItem? {
    val item = value as? JsonObject ?: return null
    val sourceId = item.text("sourceId")?.takeIf { it.length <= 128 } ?: return null
    val rawUrl = item.text("playbackUrl") ?: item.text("playableUrl") ?: return null
    val url = safeWCorePlaybackUrl(rawUrl, origin) ?: return null
    val provider = item.text("provider")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9_-]{1,32}")) }
        ?: "source"
    val coreUrl = url.startsWith("$origin/")
    val responseHeaders = item["headers"] as? JsonObject ?: item["requestHeaders"] as? JsonObject
    val headers = buildMap {
        if (coreUrl) put("Authorization", "Bearer $accessToken")
        responseHeaders?.forEach { (name, headerValue) ->
            val normalized = name.lowercase()
            if (normalized in setOf("referer", "origin", "user-agent") && !coreUrl) {
                (headerValue as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 1024 }?.let { put(name, it) }
            }
        }
    }
    val label = item.text("sourceLabel") ?: item.text("label") ?: item.text("quality")
        ?: provider.replaceFirstChar { it.uppercase() }
    val subtitles = (item["subtitles"] as? JsonArray).orEmpty().mapNotNull { subtitle ->
        val detail = subtitle as? JsonObject ?: return@mapNotNull null
        val subtitleUrl = safeWCorePlaybackUrl(detail.text("uri") ?: detail.text("url") ?: return@mapNotNull null, origin)
            ?: return@mapNotNull null
        StreamSubtitle(
            url = subtitleUrl,
            language = detail.text("language") ?: "Unknown",
            name = detail.text("title"),
            headers = if (subtitleUrl.startsWith("$origin/")) mapOf("Authorization" to "Bearer $accessToken") else null,
        )
    }
    return StreamItem(
        name = label.take(120),
        description = item.text("quality")?.take(80),
        url = url,
        sourceName = sourceId,
        addonName = item.text("providerLabel")?.take(80) ?: "W Core",
        addonId = "$W_CORE_ADDON_ID:$provider",
        streamType = item.text("container"),
        behaviorHints = StreamBehaviorHints(proxyHeaders = StreamProxyHeaders(request = headers)),
        externalSubtitles = subtitles,
    )
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

private fun safeWCorePlaybackUrl(raw: String, origin: String): String? {
    val candidate = raw.trim()
    val absolute = when {
        candidate.startsWith("/api/v1/") && !candidate.startsWith("//") -> "$origin$candidate"
        candidate.startsWith("https://", true) || candidate.startsWith("http://", true) -> candidate
        else -> return null
    }
    val authority = absolute.substringAfter("://").substringBefore('/').substringBefore('?')
    if (authority.isBlank() || '@' in authority || '\\' in absolute || '#' in absolute) return null
    return absolute
}
