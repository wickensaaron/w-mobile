package com.nuvio.app.features.streaming

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.time.Clock

/** BritBox is W's British collection. UK BritBox availability is supplied by ITVX. */
internal object BritishTvRepository {
    val providers = listOf(
        BritishTvProvider("iplayer", "BBC iPlayer", setOf("bbc.co.uk", "bbc.com")),
        BritishTvProvider("itvx", "ITVX", setOf("itv.com")),
        BritishTvProvider("all4", "Channel 4", setOf("channel4.com")),
    )
    val enabled: Boolean get() = StreamingAvailabilityRepository.config.value.let {
        it.isConfigured && it.normalizedCountry == "gb"
    }

    suspend fun loadPage(settings: StreamingApiConfig): StreamingServicePageData {
        require(settings.normalizedCountry == "gb") { "BritBox's British collection is available with the United Kingdom region (GB)." }
        val base = "/shows/search/filters?country=gb&catalogs=iplayer,itvx,all4"
        suspend fun catalogue(suffix: String) = StreamingAvailabilityRepository.parseShows(
            StreamingAvailabilityRepository.request(base + suffix, settings), wrapped = true,
        ).distinctBy { it.type to it.id }
        return withTimeoutOrNull(30_000) {
            val films = catalogue("&show_type=movie&order_by=popularity_1week")
            val series = catalogue("&show_type=series&order_by=popularity_1week")
            val more = catalogue("&show_type=series&order_by=rating")
                .filterNot { candidate -> series.any { it.id == candidate.id } }
            StreamingServicePageData(films, series, more)
        } ?: error("British catalogue request timed out. Please retry.")
    }

    suspend fun search(query: String, settings: StreamingApiConfig = StreamingAvailabilityRepository.config.value): List<MetaPreview> {
        if (!settings.isConfigured || settings.normalizedCountry != "gb" || query.isBlank()) return emptyList()
        val raw = withTimeoutOrNull(15_000) {
            StreamingAvailabilityRepository.request("/shows/search/title?country=gb&title=" + query.trim().encodeURLParameter(), settings)
        } ?: error("Broadcaster search timed out. Please retry.")
        val array = raw as? JsonArray ?: return emptyList()
        val available = JsonArray(array.filter { item ->
            (item as? JsonObject)?.let { parseBritishOffers(it, Clock.System.now().epochSeconds).isNotEmpty() } == true
        })
        return StreamingAvailabilityRepository.parseShows(available, wrapped = false).distinctBy { it.type to it.id }
    }

    suspend fun searchSection(query: String, settings: StreamingApiConfig): HomeCatalogSection = HomeCatalogSection(
        key = "british-tv:search", title = "British TV", subtitle = "BBC iPlayer · ITVX · Channel 4",
        addonName = "British TV", target = CatalogTarget.BritishSearch(query), items = search(query, settings),
    )

    suspend fun sources(type: String, canonicalId: String, settings: StreamingApiConfig): List<AddonStreamGroup> {
        val id = britishShowId(type, canonicalId) ?: return emptyList()
        if (!settings.isConfigured || settings.normalizedCountry != "gb") return emptyList()
        val raw = withTimeoutOrNull(15_000) {
            StreamingAvailabilityRepository.request("/shows/" + id + "?country=gb&series_granularity=show", settings)
        } ?: error("Broadcaster availability timed out. Please retry.")
        val show = raw as? JsonObject ?: return emptyList()
        val offers = parseBritishOffers(show, Clock.System.now().epochSeconds)
        return providers.map { provider ->
            AddonStreamGroup(addonName = provider.name, addonId = provider.groupId,
                streams = offers.filter { it.provider == provider }.map { offer ->
                    StreamItem(name = provider.name, title = "Open official player",
                        description = if (type == "series") "Choose your episode in the official player · " + offer.accessLabel
                            else "Opens the official player · " + offer.accessLabel,
                        externalUrl = offer.url, addonName = provider.name, addonId = provider.groupId,
                        sourceName = provider.name)
                })
        }
    }
}

internal data class BritishTvProvider(val id: String, val name: String, val hosts: Set<String>) {
    val groupId get() = "broadcaster:" + id
}
internal data class BritishTvOffer(val provider: BritishTvProvider, val url: String, val accessLabel: String)

internal fun britishShowId(type: String, id: String): String? {
    if (type !in setOf("movie", "series")) return null
    if (id.matches(Regex("tt[0-9]+"))) return id
    val tmdb = id.removePrefix("tmdb:").takeIf { id.startsWith("tmdb:") && it.matches(Regex("[0-9]+")) }
    return tmdb?.let { (if (type == "series") "tv/" else "movie/") + it }
}

internal fun parseBritishOffers(show: JsonObject, nowEpochSeconds: Long): List<BritishTvOffer> {
    val options = (show["streamingOptions"] as? JsonObject)?.get("gb") as? JsonArray ?: return emptyList()
    return options.mapNotNull { raw ->
        val option = raw as? JsonObject ?: return@mapNotNull null
        val serviceId = ((option["service"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
        val provider = BritishTvRepository.providers.find { it.id == serviceId } ?: return@mapNotNull null
        val expiry = (option["expiresOn"] as? JsonPrimitive)?.longOrNull
        if (expiry != null && expiry > 0 && expiry <= nowEpochSeconds) return@mapNotNull null
        val link = (option["link"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val url = runCatching { Url(link) }.getOrNull() ?: return@mapNotNull null
        if (url.protocol.name != "https" || url.port != 443 || url.user != null || url.password != null ||
            provider.hosts.none { url.host == it || url.host.endsWith("." + it) }) return@mapNotNull null
        val label = when ((option["type"] as? JsonPrimitive)?.contentOrNull) {
            "free" -> "Broadcaster account may be required"
            "subscription" -> "Subscription required"
            else -> return@mapNotNull null
        }
        BritishTvOffer(provider, link, label)
    }.distinctBy { it.provider.id to it.url }
}

/** Retain existing catalogue order and suppress additional copies of canonical British results. */
internal fun mergeBritishSearchSections(sections: List<HomeCatalogSection>): List<HomeCatalogSection> {
    val existing = sections.filterNot { it.key == "british-tv:search" }.flatMap { it.items }.map { it.type to it.id }.toSet()
    return sections.map { section ->
        if (section.key == "british-tv:search") section.copy(items = section.items.filterNot { item -> (item.alternateIds + item.id).any { (item.type to it) in existing } }) else section
    }.filter { it.items.isNotEmpty() }
}
