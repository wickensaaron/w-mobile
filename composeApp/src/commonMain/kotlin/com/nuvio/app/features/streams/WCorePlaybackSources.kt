package com.nuvio.app.features.streams

import com.nuvio.app.core.network.WCoreLibraryScope
import com.nuvio.app.core.network.WCoreLibraryOwner
import com.nuvio.app.core.network.WCoreNativeLibrary
import com.nuvio.app.core.network.isWCoreMediaId
import com.nuvio.app.core.network.WCoreConnectionRepository
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.details.MetaDetailsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
    val owner: WCoreLibraryScope,
)

internal fun canonicalCorePlaybackBody(videoId: String, preferredAudioLanguage: String?): String {
    require(isWCoreMediaId(videoId))
    return buildJsonObject {
        put("mediaId", videoId)
        preferredAudioLanguage?.trim()?.takeIf { it.length in 2..3 && it != "device" }?.let {
            put("preferredAudioLanguage", it.lowercase())
        }
    }.toString()
}

/** Adds W Core's Jellyfin and approved remote sources to Nuvio's existing source picker. */
internal object WCorePlaybackSources {
    private val selections = WCoreSourceRegistry()
    fun clearRefreshState() = selections.clear()
    fun cacheScope(): String = WCoreNativeLibrary.currentScope()?.let { "${it.account}|${it.profileId}|${it.origin}|${it.revision}" } ?: "disconnected"

    fun prepare(
        type: String,
        videoId: String,
        parentMetaId: String?,
        season: Int?,
        episode: Int?,
        preferredAudioLanguage: String?,
    ): PreparedWCorePlaybackRequest? {
        val owner = WCoreNativeLibrary.currentScope() ?: return null
        val origin = owner.origin
        val token = owner.token
        if (isWCoreMediaId(videoId)) {
            if (!WCoreNativeLibrary.canPlay(videoId)) return null
            val body = canonicalCorePlaybackBody(videoId, preferredAudioLanguage)
            return PreparedWCorePlaybackRequest(origin, token, body, owner)
        }
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
        return PreparedWCorePlaybackRequest(origin, token, identity.toString(), owner)
    }

    suspend fun load(request: PreparedWCorePlaybackRequest): AddonStreamGroup {
        currentCoroutineContext().ensureActive()
        check(WCoreNativeLibrary.isOwnerCurrent(request.owner)) {
            "W Core connection changed"
        }
        val response = requestInWCorePlaybackScope({ WCoreNativeLibrary.isOwnerCurrent(request.owner) }) { httpRequestRaw(
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
        ) }
        currentCoroutineContext().ensureActive()
        check(WCoreNativeLibrary.isOwnerCurrent(request.owner)) {
            "W Core connection changed"
        }
        if (response.status == 401 || response.status == 403) {
            WCoreConnectionRepository.retry()
        }
        return AddonStreamGroup(
            addonName = "W Core",
            addonId = W_CORE_ADDON_ID,
            streams = if (response.status in 200..299) selections.remember(
                parseWCorePlaybackSources(response.body, request.origin, request.accessToken), response.body, request,
            ) else emptyList(),
            error = when (response.status) {
                in 200..299, 404 -> null
                401, 403 -> "W Core sign-in expired"
                else -> "W Core sources unavailable"
            },
        )
    }

    /** Every native picker launch reacquires exactly the selected source with fresh credentials. */
    suspend fun refreshSelected(stream: StreamItem): StreamItem? {
        if (!stream.isWCoreStream) return stream
        val selection = selections.find(stream) ?: return null
        return try {
            withTimeoutOrNull(15_000) {
                fun fenced() {
                    check(WCoreNativeLibrary.isOwnerCurrent(selection.owner)) { "Core account changed" }
                }
                suspend fun connection(rejected: String? = null): WCoreLibraryScope {
                    fenced()
                    if (rejected != null) WCoreConnectionRepository.retry()
                    while (true) {
                        currentCoroutineContext().ensureActive(); fenced()
                        val session = WCoreNativeLibrary.currentScope()
                        if (session != null && session.token != rejected) return session
                        delay(100)
                    }
                }
                suspend fun request(session: WCoreLibraryScope): com.nuvio.app.features.addons.RawHttpResponse {
                    currentCoroutineContext().ensureActive(); fenced()
                    val result = httpRequestRaw("POST",
                        "${session.origin}/api/v1/playback/sources/${encodeWCoreSourceId(selection.sourceId)}/refresh",
                        mapOf("Authorization" to "Bearer ${session.token}", "Content-Type" to "application/json", "Accept" to "application/json"),
                        selection.body, followRedirects = false, maxResponseBodyBytes = 64 * 1024)
                    currentCoroutineContext().ensureActive(); fenced()
                    return result
                }
                var session = connection()
                var response = request(session)
                if (response.status == 401 || response.status == 403) {
                    session = connection(session.token)
                    response = request(session)
                }
                if (response.status !in 200..299) return@withTimeoutOrNull null
                val refreshed = exactWCoreRefreshedSource(parseWCorePlaybackSources(response.body, session.origin, session.token), selection.sourceId, selection.addonId)
                currentCoroutineContext().ensureActive(); fenced()
                refreshed
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
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


internal data class WCoreSourceSelection(val owner: WCoreLibraryOwner, val body: String, val sourceId: String, val addonId: String)

/** Bounded process-only identities; picker state contains no signed URL, bearer, or subtitle ticket. */
internal class WCoreSourceRegistry {
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<String, WCoreSourceSelection>()
    private var sequence = 0L
    fun clear() = synchronized(lock) { entries.clear() }
    fun find(stream: StreamItem): WCoreSourceSelection? = synchronized(lock) {
        entries[stream.url]?.takeIf { it.sourceId == stream.sourceName && it.addonId == stream.addonId }
    }
    fun remember(streams: List<StreamItem>, response: String, request: PreparedWCorePlaybackRequest): List<StreamItem> {
        val root = runCatching { Json.parseToJsonElement(response) as? JsonObject }.getOrNull() ?: return emptyList()
        val body = wCoreSourceRefreshBody(root, request.body) ?: return emptyList()
        return synchronized(lock) {
            streams.mapNotNull { stream ->
                val id = stream.sourceName ?: return@mapNotNull null
                val reference = "wcore-source://${++sequence}"
                entries[reference] = WCoreSourceSelection(request.owner.owner, body, id, stream.addonId)
                while (entries.size > 40) entries.remove(entries.keys.first())
                stream.copy(url = reference, externalSubtitles = emptyList(), behaviorHints = stream.behaviorHints.copy(proxyHeaders = null))
            }
        }
    }
}

internal fun wCoreSourceRefreshBody(response: JsonObject, originalBody: String): String? {
    val original = runCatching { Json.parseToJsonElement(originalBody) as? JsonObject }.getOrNull() ?: return null
    val mediaId = (response.text("mediaId") ?: original.text("mediaId"))?.takeIf(::isWCoreMediaId) ?: return null
    return buildJsonObject { original.forEach { (key, value) -> put(key, value) }; put("mediaId", mediaId) }.toString()
}

internal fun exactWCoreRefreshedSource(streams: List<StreamItem>, sourceId: String, addonId: String): StreamItem? =
    streams.firstOrNull { it.sourceName == sourceId && it.addonId == addonId }

internal fun encodeWCoreSourceId(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val number = byte.toInt() and 255
        if (number in 65..90 || number in 97..122 || number in 48..57 || number in listOf(45, 46, 95, 126)) append(number.toChar())
        else { append('%'); append("0123456789ABCDEF"[number shr 4]); append("0123456789ABCDEF"[number and 15]) }
    }
}


internal suspend fun <T> requestInWCorePlaybackScope(isCurrent: () -> Boolean, request: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    check(isCurrent()) { "W Core connection changed" }
    val result = request()
    currentCoroutineContext().ensureActive()
    check(isCurrent()) { "W Core connection changed" }
    return result
}
