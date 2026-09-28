package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvAccountGuidePreferencesTest {
    private val source = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val key = "a".repeat(64)
    private val prefs = """{"version":1,"hiddenGroupKeys":["$key"],"orderedGroupKeys":["$key"],
        "favouriteIds":["$source:1"],"autoTidy":true,"ukOnly":true,"collapseRegional":true}"""

    private fun response(preferences: String = prefs, catalog: String? = null): String =
        """{"revision":7,"catalogRevision":3,"preferences":$preferences${catalog?.let { ",\"catalog\":$it" }.orEmpty()}}"""

    private fun catalog(provider: String = source, channelId: String = "$source:1", groupKey: String = key): String =
        """{"groups":[{"key":"$key","name":"UK | Entertainment","providerId":"$provider"}],
            "channels":[{"id":"$channelId","name":"UK: BBC One HD","groupKey":"$groupKey"}]}"""

    @Test
    fun existingPullPayloadRetainsPreferencesRevisionsAndExactCatalogBindings() {
        val snapshot = decodeLiveTvAccountGuideSnapshot(response(catalog = catalog()), setOf(source))
        assertEquals(7L, snapshot.revision)
        assertEquals(3L, snapshot.catalogRevision)
        assertEquals(listOf("$source:1"), snapshot.preferences?.favouriteIds)
        assertEquals(key, snapshot.groupKeysByChannelId["$source:1"])
        assertEquals(LiveTvAccountGuideCatalogBinding(source, "UK: BBC One HD", "UK | Entertainment", key),
            snapshot.catalogBindingsByChannelId["$source:1"])
        assertTrue(snapshot.hasCatalog)
    }

    @Test
    fun nullPreferencesAndOmittedCatalogAreExplicitSupportedStates() {
        val snapshot = decodeLiveTvAccountGuideSnapshot(response("null"), setOf(source))
        assertEquals(null, snapshot.preferences)
        assertTrue(snapshot.groupKeysByChannelId.isEmpty())
        assertFalse(snapshot.hasCatalog)
        assertFailsWith<IllegalArgumentException> { decodeLiveTvAccountGuideSnapshot("{}", setOf(source)) }
    }

    @Test
    fun removedProviderCatalogDoesNotAcquireCurrentSourceOwnership() {
        val snapshot = decodeLiveTvAccountGuideSnapshot(response(catalog = catalog()), setOf(other))
        assertEquals(listOf("$source:1"), snapshot.preferences?.favouriteIds)
        assertTrue(snapshot.groupKeysByChannelId.isEmpty())
        assertTrue(snapshot.catalogBindingsByChannelId.isEmpty())
    }

    @Test
    fun catalogRejectsCrossProviderBindingsMissingGroupsAndDuplicateIdentities() {
        listOf(catalog(provider = other), catalog(groupKey = "b".repeat(64)),
            catalog().replace("]}", """,{"id":"$source:1","name":"Second","groupKey":"$key"}]}"""))
            .forEach {
                assertFailsWith<IllegalArgumentException> {
                    decodeLiveTvAccountGuideSnapshot(response(catalog = it), setOf(source, other))
                }
            }
    }

    @Test
    fun versionOneListsRejectUnsupportedFieldsMalformedIdsAndDuplicates() {
        listOf(
            prefs.replace("\"version\":1", "\"version\":2"),
            prefs.replace("\"ukOnly\":true", "\"ukOnly\":true,\"epgOnly\":true"),
            prefs.replace("\"$source:1\"", "\"not:a-provider:1\""),
            prefs.replace("\"hiddenGroupKeys\":[\"$key\"]", "\"hiddenGroupKeys\":[\"$key\",\"$key\"]"),
            prefs.replace("\"autoTidy\":true,", ""),
        ).forEach {
            assertFailsWith<IllegalArgumentException> { decodeLiveTvAccountGuideSnapshot(response(it), setOf(source)) }
        }
    }

    @Test
    fun sizeGatesCountUtf8AndBoundCatalogCountsBeforeExtraDecoding() {
        requireAccountGuideUtf8Budget("abc", 3)
        requireAccountGuideUtf8Budget("é", 2)
        requireAccountGuideUtf8Budget("📺", 4)
        assertFailsWith<IllegalArgumentException> { requireAccountGuideUtf8Budget("éé", 3) }
        assertFailsWith<IllegalArgumentException> { requireAccountGuideUtf8Budget("📺", 3) }
        requireAccountGuideCatalogCounts(1_000, 50_000)
        assertFailsWith<IllegalArgumentException> { requireAccountGuideCatalogCounts(1_001, 1) }
        assertFailsWith<IllegalArgumentException> { requireAccountGuideCatalogCounts(1, 50_001) }
        assertEquals(13 * 1024 * 1024, LiveTvAccountGuideWrapperBytes)
        assertEquals(12 * 1024 * 1024, LiveTvAccountGuideCatalogBytes)
        assertEquals(256 * 1024, LiveTvAccountGuidePreferenceBytes)
    }

    @Test
    fun catalogDisplayCannotCarryEndpointTextAndRevisionsCannotBeNegative() {
        assertFailsWith<IllegalArgumentException> {
            decodeLiveTvAccountGuideSnapshot(response(catalog = catalog().replace("UK: BBC One HD", "https://private.example.invalid")), setOf(source))
        }
        assertFailsWith<IllegalArgumentException> {
            decodeLiveTvAccountGuideSnapshot(response().replace("\"revision\":7", "\"revision\":-1"), setOf(source))
        }
    }

    @Test
    fun legacySafeOrganizerIdsAreRetainedButCannotBecomeImportedBindings() {
        val legacyPrefs = prefs.replace("$source:1", "legacy-provider:1")
        val snapshot = decodeLiveTvAccountGuideSnapshot(response(legacyPrefs,
            catalog(provider = "legacy-provider", channelId = "legacy-provider:1")), setOf(source))
        assertEquals(listOf("legacy-provider:1"), snapshot.preferences?.favouriteIds)
        assertTrue(snapshot.catalogBindingsByChannelId.isEmpty())
        assertEquals(null, canonicalAccountGuideProvider("legacy-provider:1"))
    }
}
