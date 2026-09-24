package com.nuvio.app.features.details

import com.nuvio.app.core.network.WCoreConnectionRepository
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal data class WCoreAcquisitionIdentity(
    val type: String,
    val title: String,
    val year: Int?,
    val tmdbId: String?,
    val imdbId: String?,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
) {
    val canRequest: Boolean get() = tmdbId != null && title.isNotBlank()
}

internal fun MetaDetails.wCoreAcquisitionIdentity(): WCoreAcquisitionIdentity? {
    val mediaType = when (type.trim().lowercase()) {
        "movie", "film" -> "movie"
        "series", "show", "tv", "tvshow" -> "series"
        else -> return null
    }
    val tmdb = Regex("(?:^|:)tmdb:(?:movie:|series:|tv:)?([0-9]+)", RegexOption.IGNORE_CASE)
        .find(id)?.groupValues?.getOrNull(1)
    val imdb = sequenceOf(imdbId, id)
        .filterNotNull()
        .mapNotNull { Regex("tt[0-9]{7,10}", RegexOption.IGNORE_CASE).find(it)?.value?.lowercase() }
        .firstOrNull()
    if (tmdb == null && imdb == null) return null
    val year = Regex("(?:18|19|20|21)[0-9]{2}").find(releaseInfo.orEmpty())
        ?.value?.toIntOrNull()
    return WCoreAcquisitionIdentity(
        mediaType, name.trim().take(300), year, tmdb, imdb,
        poster?.takeIf { it.startsWith("https://") && it.length <= 2000 },
        background?.takeIf { it.startsWith("https://") && it.length <= 2000 },
    )
}

internal enum class WCoreAcquisitionState {
    NOT_REQUESTED, QUEUED, DOWNLOADING, PAUSED, AVAILABLE, FAILED
}

internal data class WCoreAcquisitionStatus(
    val state: WCoreAcquisitionState,
    val acquisitionId: String? = null,
    val progressPercent: Int? = null,
    val canRetry: Boolean = false,
)

internal class WCoreAcquisitionClient(
    private val connection: () -> Pair<String, String>? = WCoreConnectionRepository::currentConnection,
    private val transport: suspend (String, String, Map<String, String>, String) -> RawHttpResponse =
        { method, url, headers, body ->
            httpRequestRaw(method, url, headers, body, followRedirects = false, maxResponseBodyBytes = 256 * 1024)
        },
) {
    suspend fun status(identity: WCoreAcquisitionIdentity): WCoreAcquisitionStatus {
        val session = connection() ?: throw WCoreAcquisitionUnavailable()
        val mediaId = findCanonicalId(session, identity) ?: return WCoreAcquisitionStatus(WCoreAcquisitionState.NOT_REQUESTED)
        val availableByMapping = hasJellyfinMapping(session, mediaId)
        val job = findAcquisition(session, mediaId)
        checkSession(session)
        return job?.let(::parseAcquisition) ?: WCoreAcquisitionStatus(
            if (availableByMapping) WCoreAcquisitionState.AVAILABLE else WCoreAcquisitionState.NOT_REQUESTED,
        )
    }

    suspend fun request(identity: WCoreAcquisitionIdentity): WCoreAcquisitionStatus {
        if (!identity.canRequest) throw WCoreAcquisitionUnavailable()
        val session = connection() ?: throw WCoreAcquisitionUnavailable()
        val body = buildJsonObject {
            put("type", identity.type)
            put("title", identity.title)
            identity.year?.let { put("year", it) }
            put("externalIds", buildJsonObject {
                identity.tmdbId?.let { put("tmdb", it) }
                identity.imdbId?.let { put("imdb", it) }
            })
        }.toString()
        val resolved = call(session, "POST", "/api/v1/media/resolve", body)
        val mediaId = parseCanonicalId(resolved) ?: throw WCoreAcquisitionUnavailable()
        checkSession(session)
        val created = call(session, "POST", "/api/v1/acquisitions", buildJsonObject {
            put("mediaId", mediaId)
            put("jobType", "ADD_TO_LIBRARY")
            put("action", "ADD_TO_LIBRARY")
            identity.posterUrl?.let { put("posterUrl", it) }
            identity.backdropUrl?.let { put("backdropUrl", it) }
        }.toString())
        checkSession(session)
        return parseAcquisition(parseObject(created)["item"] as? JsonObject ?: throw WCoreAcquisitionUnavailable())
    }

    suspend fun retry(acquisitionId: String): WCoreAcquisitionStatus {
        if (!acquisitionId.matches(Regex("[0-9a-fA-F-]{36}"))) throw WCoreAcquisitionUnavailable()
        val session = connection() ?: throw WCoreAcquisitionUnavailable()
        val response = call(session, "POST", "/api/v1/acquisitions/$acquisitionId/retry", "{}")
        checkSession(session)
        return parseAcquisition(parseObject(response)["item"] as? JsonObject ?: throw WCoreAcquisitionUnavailable())
    }

    private suspend fun findCanonicalId(
        session: Pair<String, String>, identity: WCoreAcquisitionIdentity,
    ): String? {
        val candidates = listOfNotNull(
            identity.tmdbId?.let { "tmdb" to it },
            identity.imdbId?.let { "imdb" to it },
        )
        for ((provider, externalId) in candidates) {
            val path = "/api/v1/media/resolve?provider=$provider&externalId=$externalId&mediaType=${identity.type}"
            val response = call(session, "GET", path, allowNotFound = true) ?: continue
            parseCanonicalId(response)?.let { return it }
        }
        return null
    }

    private suspend fun hasJellyfinMapping(session: Pair<String, String>, mediaId: String): Boolean {
        val response = call(session, "GET", "/api/v1/media/$mediaId/mappings")
        val mappings = parseObject(response)["mappings"] as? JsonArray ?: throw WCoreAcquisitionUnavailable()
        return mappings.any { (it as? JsonObject)?.string("provider") == "jellyfin" }
    }

    private suspend fun findAcquisition(session: Pair<String, String>, mediaId: String): JsonObject? {
        val page = parseObject(call(session, "GET", "/api/v1/acquisitions?mediaId=$mediaId&limit=1"))
        val items = page["items"] as? JsonArray ?: throw WCoreAcquisitionUnavailable()
        val item = items.firstOrNull() as? JsonObject ?: return null
        if (item.string("mediaId") != mediaId) throw WCoreAcquisitionUnavailable()
        return item
    }

    private suspend fun call(
        session: Pair<String, String>, method: String, path: String,
        body: String = "", allowNotFound: Boolean = false,
    ): String? {
        checkSession(session)
        val response = try {
            transport(
                method, "${session.first}$path",
                mapOf(
                    "Authorization" to "Bearer ${session.second}",
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                ),
                body,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw WCoreAcquisitionUnavailable()
        }
        checkSession(session)
        if (allowNotFound && response.status == 404) return null
        if (response.status == 401 || response.status == 403) WCoreConnectionRepository.retry()
        if (response.status !in 200..299) throw WCoreAcquisitionUnavailable()
        return response.body
    }

    private fun checkSession(session: Pair<String, String>) {
        if (connection() != session) throw WCoreAcquisitionUnavailable()
    }
}

internal class WCoreAcquisitionUnavailable : Exception()

internal fun parseAcquisition(item: JsonObject): WCoreAcquisitionStatus {
    val request = item["request"] as? JsonObject ?: throw WCoreAcquisitionUnavailable()
    val transfer = item["transfer"] as? JsonObject ?: throw WCoreAcquisitionUnavailable()
    val library = item["library"] as? JsonObject ?: throw WCoreAcquisitionUnavailable()
    val acquisition = item["acquisition"] as? JsonObject ?: throw WCoreAcquisitionUnavailable()
    val actions = item["allowedActions"] as? JsonObject
    val state = when {
        library.string("state") == "available" -> WCoreAcquisitionState.AVAILABLE
        request.string("state") == "failed" || acquisition.string("state") == "failed" ||
            transfer.string("state") == "failed" || library.string("state") == "failed" -> WCoreAcquisitionState.FAILED
        request.string("state") == "paused" -> WCoreAcquisitionState.PAUSED
        transfer.string("state") == "active" -> WCoreAcquisitionState.DOWNLOADING
        else -> WCoreAcquisitionState.QUEUED
    }
    val progress = (transfer["progress"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        ?.takeIf { it in 0.0..1.0 }?.let { (it * 100).toInt() }
    return WCoreAcquisitionStatus(
        state = state,
        acquisitionId = item.string("id"),
        progressPercent = progress,
        canRetry = (actions?.get("retry") as? JsonPrimitive)?.contentOrNull == "true",
    )
}

private fun parseCanonicalId(body: String?): String? =
    body?.let(::parseObject)?.get("media")
        ?.let { it as? JsonObject }?.string("id")
        ?.takeIf { it.matches(Regex("wm_[A-Za-z0-9_-]{8,64}")) }

private fun parseObject(body: String?): JsonObject =
    try { Json.parseToJsonElement(body.orEmpty()).jsonObject }
    catch (_: Throwable) { throw WCoreAcquisitionUnavailable() }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
