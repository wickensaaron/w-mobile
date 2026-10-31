package com.nuvio.app.features.streaming

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.streams.*
import kotlinx.serialization.json.*
import kotlin.test.*

class BritishTvIntegrationTest {
    private fun offers(link: String = "https://www.bbc.co.uk/iplayer/episode/p1234567", service: String = "iplayer",
        country: String = "gb", expiry: Long? = null, type: String = "free"): JsonObject = buildJsonObject {
        putJsonObject("streamingOptions") { putJsonArray(country) { addJsonObject {
            putJsonObject("service") { put("id", service) }; put("link", link); put("type", type)
            expiry?.let { put("expiresOn", it) }
        } } }
    }
    @Test fun preservesAllExistingServiceTiles() {
        assertEquals(listOf("Netflix", "Prime Video", "Disney+", "Apple TV", "BritBox"), StreamingService.entries.map { it.displayName })
    }
    @Test fun canonicalIdsDoNotConfuseEpisodesOrServerIds() {
        assertEquals("tt12345", britishShowId("series", "tt12345"))
        assertEquals("tv/123", britishShowId("series", "tmdb:123"))
        assertEquals("movie/123", britishShowId("movie", "tmdb:123"))
        for (id in listOf("tt123:1:2", "tmdb:123:1:2", "server:123", "123", "tmdb:1/../../")) assertNull(britishShowId("series", id))
        assertNull(britishShowId("channel", "tt123"))
    }
    @Test fun rejectsExpiredAndWrongRegionOffers() {
        assertEquals(1, parseBritishOffers(offers(expiry = 101), 100).size)
        assertTrue(parseBritishOffers(offers(expiry = 100), 100).isEmpty())
        assertTrue(parseBritishOffers(offers(country = "us"), 100).isEmpty())
        assertTrue(parseBritishOffers(offers(service = "netflix"), 100).isEmpty())
        assertTrue(parseBritishOffers(offers(type = "rent"), 100).isEmpty())
    }
    @Test fun onlyAcceptsSecureOfficialProviderLinks() {
        for (url in listOf("http://www.bbc.co.uk/iplayer", "https://bbc.co.uk.attacker.test/", "https://notbbc.co.uk/", "https://evil.test/?next=bbc.co.uk", "https://user@bbc.co.uk/", "https://bbc.co.uk:8443/", "javascript:alert(1)")) {
            assertTrue(parseBritishOffers(offers(link = url), 100).isEmpty(), url)
        }
        assertEquals(1, parseBritishOffers(offers(link = "https://www.itv.com/watch/show/123", service = "itvx"), 100).size)
        assertTrue(parseBritishOffers(offers(link = "https://www.itv.com/watch/show/123", service = "iplayer"), 100).isEmpty())
    }
    @Test fun malformedOffersDoNotBreakOtherSources() {
        assertTrue(parseBritishOffers(Json.parseToJsonElement("""{"streamingOptions":{"gb":[null,{}, {"service":{"id":{}}}]}}""").jsonObject, 100).isEmpty())
    }
    @Test fun broadcasterAvailabilityChecksDoNotHoldUpNativeAutoplay() {
        val groups = listOf(AddonStreamGroup("BBC iPlayer", "broadcaster:iplayer", emptyList(), isLoading = true))
        assertTrue(groups.areAutoPlaySourcesLoaded(StreamAutoPlaySource.ALL_SOURCES, emptySet()))
        assertFalse((groups + AddonStreamGroup("Native", "addon:native", emptyList(), isLoading = true)).areAutoPlaySourcesLoaded(StreamAutoPlaySource.ALL_SOURCES, setOf("addon:native")))
    }
    @Test fun officialSourcesCannotBeAutoPlayedOrTreatedAsNativeMedia() {
        val stream = StreamItem(name = "BBC iPlayer", addonName = "BBC iPlayer", addonId = "broadcaster:iplayer", externalUrl = "https://www.bbc.co.uk/iplayer/episode/p1234567")
        assertTrue(stream.shouldOpenExternally)
        assertNull(stream.playableDirectUrl)
        assertNull(StreamAutoPlaySelector.selectAutoPlayStream(listOf(stream), StreamAutoPlayMode.FIRST_STREAM, "", StreamAutoPlaySource.ALL_SOURCES,
            emptySet(), emptySet(), emptySet(), balancedSelection = false))
    }
    private fun section(key: String, items: List<MetaPreview>) = HomeCatalogSection(key, key, "", "", CatalogTarget.BritishSearch("Example"), items)
    @Test fun laterSearchResultsDoNotDuplicateCanonicalBritishTitles() {
        val title = MetaPreview("tt123", "series", "Example")
        val extra = MetaPreview("tt456", "series", "Another")
        val british = section("british-tv:search", listOf(title, extra))
        assertEquals(2, mergeBritishSearchSections(listOf(british)).single().items.size)
        val merged = mergeBritishSearchSections(listOf(section("addon", listOf(title)), british))
        assertEquals(listOf(extra), merged.last().items)
        assertEquals(listOf(title), merged.first().items)
        assertEquals(1, mergeBritishSearchSections(listOf(section("addon", listOf(title, extra)), british)).size)
    }
    @Test fun imdbAndTmdbSearchResultsForTheSameTitleMerge() {
        val imdb = MetaPreview("tt123", "series", "Example", alternateIds = setOf("tmdb:456"))
        val tmdb = MetaPreview("tmdb:456", "series", "Example")
        assertEquals(1, mergeBritishSearchSections(listOf(section("addon", listOf(tmdb)), section("british-tv:search", listOf(imdb)))).size)
    }
    @Test fun liveChoicesDoNotMapTimeshiftOrRegionalChannelsToAnotherBroadcast() {
        assertEquals("BBC iPlayer", britishLiveDestination("BBC ONE HD")?.name)
        assertEquals("ITVX", britishLiveDestination("ITV2")?.name)
        for (name in listOf("BBC ONE +1", "ITV1 +1", "ITV1 London", "BBC One Scotland", "BBC Earth", "ITVBe", "Unknown")) assertNull(britishLiveDestination(name), name)
    }
}
