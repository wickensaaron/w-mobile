package com.nuvio.app.features.streaming

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class StreamingService(val apiId: String, val displayName: String) {
    NETFLIX("netflix", "Netflix"),
    PRIME("prime", "Prime Video"),
    DISNEY("disney", "Disney+"),
    APPLE("apple", "Apple TV");

    companion object {
        fun fromId(id: String): StreamingService? = entries.firstOrNull { it.apiId == id }
    }
}

@Serializable
data class StreamingApiConfig(
    val apiKey: String = "",
    val source: String = "direct",
    val country: String = "gb",
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()
    val normalizedCountry: String get() = country.trim().lowercase().takeIf { it.matches(Regex("[a-z]{2}")) } ?: "gb"
}

internal expect object StreamingAvailabilityStorage {
    fun loadConfig(): String?
    fun saveConfig(value: String)
}

data class StreamingServicePageData(
    val topFilms: List<MetaPreview>,
    val topSeries: List<MetaPreview>,
    val more: List<MetaPreview>,
)

object StreamingAvailabilityRepository {
    private data class CachedPage(val loadedAt: TimeMark, val data: StreamingServicePageData)
    private val json = Json { ignoreUnknownKeys = true }
    private val _config = MutableStateFlow(loadConfig())
    val config = _config.asStateFlow()
    private val pageCache = mutableMapOf<String, CachedPage>()

    fun saveConfig(apiKey: String, source: String, country: String) {
        val value = StreamingApiConfig(
            apiKey = apiKey.trim(),
            source = if (source == "rapidapi") "rapidapi" else "direct",
            country = country.trim().lowercase().takeIf { it.matches(Regex("[a-z]{2}")) } ?: "gb",
        )
        _config.value = value
        StreamingAvailabilityStorage.saveConfig(json.encodeToString(StreamingApiConfig.serializer(), value))
        pageCache.clear()
    }

    suspend fun loadPage(service: StreamingService, force: Boolean = false): StreamingServicePageData {
        val settings = config.value
        require(settings.isConfigured) { "Add your Streaming Availability API key to load this service." }
        val cacheKey = "${settings.source}:${settings.normalizedCountry}:${settings.apiKey}:${service.apiId}"
        if (!force) pageCache[cacheKey]?.takeIf { it.loadedAt.elapsedNow() < 6.hours }?.let { return it.data }
        val country = settings.normalizedCountry
        val topBase = "/shows/top?country=$country&service=${service.apiId}"
        val catalogBase = "/shows/search/filters?country=$country&catalogs=${service.apiId}.subscription"
        val films = if (service == StreamingService.APPLE) {
            parseShows(request("$catalogBase&order_by=popularity_1week&show_type=movie", settings), wrapped = true).take(10)
        } else {
            parseShows(request("$topBase&show_type=movie", settings), wrapped = false)
        }
        val series = if (service == StreamingService.APPLE) {
            parseShows(request("$catalogBase&order_by=popularity_1week&show_type=series", settings), wrapped = true).take(10)
        } else {
            parseShows(request("$topBase&show_type=series", settings), wrapped = false)
        }
        val more = try {
            parseShows(
                request(
                    "$catalogBase&order_by=${if (service == StreamingService.APPLE) "rating" else "popularity_1week"}&show_type=movie",
                    settings,
                ),
                wrapped = true,
            ).filterNot { candidate -> films.any { it.id == candidate.id } }.take(20)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        return StreamingServicePageData(films, series, more).also {
            pageCache[cacheKey] = CachedPage(TimeSource.Monotonic.markNow(), it)
        }
    }

    suspend fun loadDisneyHub(brand: String): List<MetaPreview> {
        val settings = config.value
        require(settings.isConfigured)
        val keyword = when (brand) {
            "Pixar" -> "pixar"
            "Marvel" -> "marvel"
            "Star Wars" -> "star%20wars"
            "Nat Geo" -> "national%20geographic"
            else -> "disney"
        }
        return parseShows(
            request(
                "/shows/search/filters?country=${settings.normalizedCountry}&catalogs=disney.subscription" +
                    "&order_by=popularity_1week&keyword=$keyword",
                settings,
            ),
            wrapped = true,
        )
    }

    private suspend fun request(path: String, settings: StreamingApiConfig): kotlinx.serialization.json.JsonElement {
        val rapid = settings.source == "rapidapi"
        val base = if (rapid) "https://streaming-availability.p.rapidapi.com" else "https://api.movieofthenight.com/v4"
        val header = if (rapid) "X-RapidAPI-Key" else "X-API-Key"
        return json.parseToJsonElement(httpGetTextWithHeaders(base + path, mapOf(header to settings.apiKey)))
    }

    internal fun parseShows(raw: kotlinx.serialization.json.JsonElement, wrapped: Boolean): List<MetaPreview> {
        val entries = if (wrapped) raw.jsonObject["shows"] as? JsonArray else raw as? JsonArray
        return entries?.mapNotNull { (it as? JsonObject)?.toPreview() }.orEmpty()
    }

    private fun loadConfig(): StreamingApiConfig = runCatching {
        StreamingAvailabilityStorage.loadConfig()?.let {
            json.decodeFromString(StreamingApiConfig.serializer(), it)
        }
    }.getOrNull() ?: StreamingApiConfig()
}

private fun JsonObject.toPreview(): MetaPreview? {
    val tmdbId = string("tmdbId")?.takeIf { it.isNotBlank() }
    val imdbId = string("imdbId")?.takeIf { it.isNotBlank() }
    val id = imdbId ?: tmdbId?.substringAfterLast('/')?.toIntOrNull()?.let { "tmdb:$it" } ?: return null
    val title = string("title")?.takeIf { it.isNotBlank() } ?: return null
    val images = this["imageSet"] as? JsonObject
    val poster = (images?.get("verticalPoster") as? JsonObject)?.string("w360")
        ?: (images?.get("verticalPoster") as? JsonObject)?.string("w480")
    val backdrop = (images?.get("horizontalBackdrop") as? JsonObject)?.string("w1080")
        ?: (images?.get("horizontalPoster") as? JsonObject)?.string("w720")
    return MetaPreview(
        id = id,
        type = if (string("showType") == "series") "series" else "movie",
        name = title,
        poster = poster,
        banner = backdrop,
        description = string("overview"),
        releaseInfo = string("releaseYear"),
    )
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
