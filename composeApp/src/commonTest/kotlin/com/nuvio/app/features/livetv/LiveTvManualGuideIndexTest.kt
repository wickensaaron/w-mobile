package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvManualGuideIndexTest {
    private fun channel(key: String) = LiveTvChannel("manual", "Channel", "https://stream.example.invalid", guideId = key)
    private fun row(title: String) = LiveTvProgramme("guide", title, startEpochMs = 1, stopEpochMs = 100)

    @Test
    fun indexRetainsExactFirstAndEqualsIgnoreCaseUnicodeBehavior() {
        val index = LiveTvManualGuideIndex(linkedMapOf("bbc" to listOf(row("First")),
            "BBC" to listOf(row("Exact")), "K" to listOf(row("Kelvin"))))
        assertEquals("Exact", index.programmesFor(channel("BBC")).single().title)
        assertEquals("First", index.programmesFor(channel("BbC")).single().title)
        assertEquals("Kelvin", index.programmesFor(channel("k")).single().title)
    }

    @Test
    fun manyLookupsDoNotScanProgrammesRepeatedly() {
        var reads = 0
        val backing = linkedMapOf("bbc" to listOf(row("BBC")))
        val counted = object : Map<String, List<LiveTvProgramme>> by backing {
            override val entries: Set<Map.Entry<String, List<LiveTvProgramme>>>
                get() { reads++; return backing.entries }
        }
        val index = LiveTvManualGuideIndex(counted)
        val afterPreparation = reads
        repeat(1_000) { assertTrue(index.hasUnexpiredGuide(channel("BBC"), 50)) }
        assertEquals(afterPreparation, reads)
        assertFalse(index.hasUnexpiredGuide(channel("BBC"), 100))
    }

    @Test
    fun manualIndexExcludesImportedEntriesAndNeverSuppliesImportedObjects() {
        val owner = LiveTvAccountScope("https://account.example.invalid", "owner", 1)
        val index = LiveTvManualGuideIndex(mapOf("imported:1" to listOf(row("Account")), "bbc" to listOf(row("Manual"))),
            excludedChannelIds = setOf("imported:1"))
        assertTrue(index.programmesFor(channel("imported:1")).isEmpty())
        assertTrue(index.programmesFor(channel("bbc").copy(accountScope = owner)).isEmpty())
        assertEquals("Manual", index.programmesFor(channel("bbc")).single().title)
    }
}
