package com.nuvio.app.features.livetv

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LiveTvAccountGuidePresentationTest {
    private val source = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val owner = LiveTvAccountScope("https://account.example.invalid", "owner", 1)
    private val now = 100_000L
    private val generation = 7L

    private fun channel(id: String, name: String, provider: String = source, group: String? = "UK | Entertainment") =
        LiveTvChannel("$provider:$id", name, "https://stream.example.invalid/$provider/$id", group = group,
            playlistId = provider, guideId = "epg-$id", accountScope = owner, accountSourceGeneration = generation,
            sourceLoadGeneration = 4)

    private fun row(channel: LiveTvChannel, title: String = "Programme", start: Long = now - 1_000, stop: Long = now + 1_000) =
        LiveTvProgramme(channel.id, title, startEpochMs = start, stopEpochMs = stop)

    private suspend fun project(channels: List<LiveTvChannel>, programmes: Map<String, List<LiveTvProgramme>> =
        channels.associate { it.id to listOf(row(it)) }, prefs: LiveTvAccountGuidePreferences? = null,
        snapshot: LiveTvAccountGuideSnapshot = LiveTvAccountGuideSnapshot(preferences = prefs), favoritesOnly: Boolean = false) =
        projectLiveTvAccountGuide(channels, programmes, snapshot, listOf(source, other), owner, generation, now, favoritesOnly)

    @Test
    fun ukEligibilityRequiresCountryEvidenceNotFamiliarOrAmbiguousStationNames() = runTest {
        val rows = listOf(channel("1", "BBC One", group = "International"),
            channel("2", "TLC", group = "Entertainment"), channel("3", "UK: BBC One HD", group = "Entertainment"),
            channel("4", "Sky Sports Main Event", group = "🇬🇧 Sports"), channel("5", "UKraine News", group = "Ukraine"))
        assertEquals(listOf(rows[2], rows[3]), project(rows).rows.map { it.channel })
        assertFalse(isExplicitUkAccountGuideChannel(rows[0]))
        assertFalse(isExplicitUkAccountGuideChannel(rows[4]))
    }

    @Test
    fun onlySourceOwnedNonblankUnexpiredGuideRowsQualify() = runTest {
        val channels = (1..7).map { channel("$it", "UK: Service $it") }
        val guide = mapOf(
            channels[0].id to listOf(row(channels[0])),
            channels[1].id to listOf(row(channels[1], start = now + 1_000, stop = now + 2_000)),
            channels[2].id to listOf(row(channels[2], stop = now)),
            channels[3].id to listOf(row(channels[3], title = " ")),
            channels[4].id to listOf(row(channels[0])),
            channels[5].id to listOf(row(channels[5], start = now + 25L * 60 * 60 * 1000, stop = now + 26L * 60 * 60 * 1000)),
            channels[6].id to listOf(row(channels[6], start = now + 2_000, stop = now + 1_000)),
            "epg-7" to listOf(row(channels[6])),
        )
        val result = project(channels, guide)
        assertEquals(channels.take(2), result.rows.map { it.channel })
        assertEquals(5, result.channelsWithoutGuide)
        assertEquals(channels, result.rawAlternatives)
    }

    @Test
    fun guideBackedWinnerKeepsRawObjectAndExactBroadcastAlternatives() = runTest {
        val missing = channel("1", "UK: BBC One UHD")
        val guided = channel("2", "UK: BBC1 HD (Backup)")
        val regional = channel("3", "UK: BBC One London HD")
        val result = project(listOf(missing, guided, regional), mapOf(guided.id to listOf(row(guided)),
            regional.id to listOf(row(regional))))
        val selected = result.rows.single()
        assertSame(guided, selected.channel)
        assertEquals("BBC One", selected.displayName)
        assertEquals("All UK", selected.displayGroup)
        assertEquals(101, selected.channelNumber)
        assertEquals(listOf(guided, missing), selected.alternatives)
        assertFalse(selected.alternatives.contains(regional))
        assertEquals("UK: BBC1 HD (Backup)", guided.name)
        assertEquals("UK | Entertainment", guided.group)
    }

    @Test
    fun providerPriorityPrecedesQualityAfterGuideAvailability() = runTest {
        val primary = channel("1", "UK: Sky Sports Main Event HD")
        val higherQuality = channel("2", "UK: Sky Sports Main Event UHD", provider = other)
        assertSame(primary, project(listOf(higherQuality, primary)).rows.single().channel)
        val hd = channel("3", "UK: ITV1 HD")
        val uhd = channel("4", "UK: ITV1 UHD")
        assertSame(uhd, project(listOf(hd, uhd)).rows.single().channel)
    }

    @Test
    fun regionsCollapseButTimeshiftsDistinctBroadcastersAndUnknownSuffixesDoNot() = runTest {
        val rows = listOf(channel("1", "UK: ITV1 London HD"), channel("2", "UK: ITV HD"),
            channel("3", "UK: ITV1 +1 HD"), channel("4", "UK: ITV1 London +1 HD"),
            channel("5", "UK: STV HD"), channel("6", "UK: UTV HD"),
            channel("7", "UK: BBC One Special HD"), channel("8", "UK: BBC One Special UHD"))
        val result = project(rows)
        assertEquals(setOf(rows[1], rows[2], rows[4], rows[5], rows[6], rows[7]), result.rows.map { it.channel }.toSet())
        assertEquals(setOf(103, 203), result.rows.mapNotNull { it.channelNumber }.toSet())
        assertEquals(2, result.duplicatesHidden)
    }

    @Test
    fun hiddenCategoriesAffectAllButExactSavedFavoritesStayReachable() = runTest {
        val saved = channel("1", "UK: BBC One HD")
        val better = channel("2", "UK: BBC One UHD", group = "UK | Other TV")
        val hiddenKey = liveTvAccountGuideGroupKey(source, saved.group!!)
        val prefs = LiveTvAccountGuidePreferences(hiddenGroupKeys = listOf(hiddenKey), favouriteIds = listOf(saved.id))
        assertSame(better, project(listOf(saved, better), prefs = prefs).rows.single().channel)
        assertSame(saved, project(listOf(saved, better), prefs = prefs, favoritesOnly = true).rows.single().channel)
        assertTrue(project(listOf(saved.copy(accountScope = owner.copy(profile = 2)), better), prefs = prefs, favoritesOnly = true).rows.isEmpty())
    }

    @Test
    fun accountProfileBackendGenerationDisabledAndAmbiguousSourcesCannotEnterProjection() = runTest {
        val raw = channel("1", "UK: BBC One HD")
        val wrong = listOf(raw.copy(accountScope = owner.copy(account = "other")),
            raw.copy(accountScope = owner.copy(profile = 2)),
            raw.copy(accountScope = owner.copy(backend = "https://different.example.invalid")),
            raw.copy(accountSourceGeneration = generation + 1),
            raw.copy(playlistId = other), raw.copy(accountScope = null), raw.copy(id = "invalid:1"))
        wrong.forEach { assertTrue(project(listOf(it)).rows.isEmpty()) }
        assertTrue(projectLiveTvAccountGuide(listOf(raw), mapOf(raw.id to listOf(row(raw))), LiveTvAccountGuideSnapshot(),
            emptyList(), owner, generation, now).rows.isEmpty())
        assertTrue(project(listOf(raw, raw)).rows.isEmpty())
    }

    @Test
    fun catalogKeyRequiresOriginalLabelsAndNeverMatchesAReindexedBroadcast() = runTest {
        val raw = channel("1", "UK: BBC One HD", group = null)
        val key = "a".repeat(64)
        val binding = LiveTvAccountGuideCatalogBinding(source, raw.name, "Other", key)
        val snapshot = LiveTvAccountGuideSnapshot(preferences = LiveTvAccountGuidePreferences(hiddenGroupKeys = listOf(key)),
            groupKeysByChannelId = mapOf(raw.id to key), hasCatalog = true, catalogBindingsByChannelId = mapOf(raw.id to binding))
        assertTrue(project(listOf(raw), snapshot = snapshot).rows.isEmpty())
        val changed = raw.copy(name = "UK: BBC Two HD")
        assertEquals(listOf(changed), project(listOf(changed), snapshot = snapshot).rows.map { it.channel })
        assertEquals(null, accountGuideCatalogGroupKey(changed, snapshot))
        assertEquals(null, accountGuideCatalogGroupKey(raw.copy(group = "UK | News"), snapshot))
        assertEquals(null, accountGuideCatalogGroupKey(raw, snapshot.copy(catalogBindingsByChannelId =
            mapOf(raw.id to binding.copy(providerId = other)))))
    }

    @Test
    fun skyQOrderHasNoInventedUnknownNumbersAndCategoryRankIsStable() = runTest {
        val rows = listOf(channel("1", "UK: Sky Sports Main Event HD", group = "UK | Sports"),
            channel("2", "UK: Unknown Station", group = "UK | Entertainment"),
            channel("3", "UK: BBC Two HD"), channel("4", "UK: BBC One HD"))
        val result = project(rows)
        assertEquals(listOf(rows[3], rows[2], rows[0], rows[1]), result.rows.map { it.channel })
        assertEquals(null, result.rows.last().channelNumber)
        val sportsKey = liveTvAccountGuideGroupKey(source, rows[0].group!!)
        val ordered = project(rows, prefs = LiveTvAccountGuidePreferences(orderedGroupKeys = listOf(sportsKey)))
        assertSame(rows[0], ordered.rows.first().channel)
        assertEquals(listOf(rows[3], rows[2], rows[1]), ordered.rows.drop(1).map { it.channel })
        assertEquals(listOf(rows[0]), filterLiveTvAccountGuideRows(result.rows, "401").map { it.channel })
        assertEquals(listOf(rows[3]), filterLiveTvAccountGuideRows(result.rows, "bbc one").map { it.channel })
        assertSame(result.rows, filterLiveTvAccountGuideRows(result.rows, " "))
    }

    @Test
    fun largeProjectionYieldsAndCancelsBeforePublication() = runTest {
        var published = false
        val channels = List(1_000) { channel("$it", "UK: Service $it") }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            project(channels)
            published = true
        }
        job.cancel()
        job.join()
        assertFalse(published)
    }

    @Test
    fun turningTidyOffKeepsRawTitlesAndSharesOnePreparedAlternateList() = runTest {
        val hd = channel("1", "UK: BBC One HD")
        val uhd = channel("2", "UK: BBC One UHD")
        val result = project(listOf(hd, uhd), prefs = LiveTvAccountGuidePreferences(autoTidy = false))
        assertEquals(listOf(hd.name, uhd.name), result.rows.map { it.displayName })
        assertEquals(listOf(hd, uhd), result.rows.map { it.channel })
        assertSame(result.rows[0].alternatives, result.rows[1].alternatives)
        assertEquals(listOf(uhd, hd), result.rows[0].alternatives)
        assertFalse(result.rows.toString().contains("stream.example.invalid"))
    }

    @Test
    fun unknownOtherCategoryNeverBorrowsANameMatchedCatalogKey() = runTest {
        val raw = channel("1", "UK: BBC One HD", group = "Other")
        val key = "a".repeat(64)
        val differentId = "$source:2"
        val binding = LiveTvAccountGuideCatalogBinding(source, raw.name, "Other", key)
        val snapshot = LiveTvAccountGuideSnapshot(preferences = LiveTvAccountGuidePreferences(hiddenGroupKeys = listOf(key)),
            groupKeysByChannelId = mapOf(differentId to key), hasCatalog = true,
            catalogBindingsByChannelId = mapOf(differentId to binding))
        assertSame(raw, project(listOf(raw), snapshot = snapshot).rows.single().channel)
        assertEquals(null, project(listOf(raw), snapshot = snapshot).rows.single().groupKey)
    }
}
