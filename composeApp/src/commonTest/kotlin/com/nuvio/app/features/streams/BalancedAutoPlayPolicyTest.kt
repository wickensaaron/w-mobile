package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BalancedAutoPlayPolicyTest {
    private fun source(size: Long? = null, languages: List<String> = emptyList(), name: String = "1080p") = StreamItem(
        name = name, url = "https://example.invalid/video", addonName = "Test", addonId = "addon:test",
        behaviorHints = StreamBehaviorHints(videoSize = size),
        clientResolve = StreamClientResolve(stream = StreamClientResolveStream(raw = StreamClientResolveRaw(
            parsed = StreamClientResolveParsed(languages = languages)))),
    )

    private fun choose(streams: List<StreamItem>, mode: StreamAutoPlayMode = StreamAutoPlayMode.SMART,
        binge: String? = null) = StreamAutoPlaySelector.evaluateAutoPlayStream(
        streams = streams, mode = mode, regexPattern = ".*", source = StreamAutoPlaySource.ALL_SOURCES,
        installedAddonNames = setOf("Test"), selectedAddons = emptySet(), selectedPlugins = emptySet(),
        preferredBingeGroup = binge, preferBingeGroupInSelection = binge != null, balancedSelection = true,
    )

    @Test fun oversizedJellyfinCannotBeatReasonable1080p() {
        val large = source(45_000_000_000, listOf("en"), "4K").copy(addonId = "wcore:jellyfin")
        val balanced = source(8_000_000_000, listOf("eng"))
        assertEquals(listOf(balanced), choose(listOf(large, balanced)).readyStreams)
    }
    @Test fun sizePreferenceOutweighsSourcePriorityWithinCeiling() {
        val large = source(19_000_000_000, listOf("en"), "4K").copy(addonId = "wcore:jellyfin")
        val balanced = source(8_000_000_000, listOf("en"))
        assertEquals(balanced, choose(listOf(large, balanced)).stream)
    }
    @Test fun exactlyTwentyGbIsAllowedButOneByteOverIsNot() {
        assertTrue(BalancedAutoPlayPolicy.isEligible(source(20_000_000_000)))
        assertFalse(BalancedAutoPlayPolicy.isEligible(source(20_000_000_001)))
    }
    @Test fun titleSizeUsesCorrectDecimalAndBinaryUnits() {
        assertEquals(40_500_000_000, BalancedAutoPlayPolicy.sizeBytes(source(name = "4K 40.5 GB")))
        assertEquals(21_474_836_480, BalancedAutoPlayPolicy.sizeBytes(source(name = "20 GiB")))
        assertFalse(BalancedAutoPlayPolicy.isEligible(source(name = "20 GiB")))
    }
    @Test fun structuredSizeTakesPrecedenceOverAmbiguousTitle() {
        assertEquals(8_000_000_000, BalancedAutoPlayPolicy.sizeBytes(source(8_000_000_000, name = "Season pack 70 GB")))
    }
    @Test fun seasonPackFolderSizeIsNotVideoSize() {
        val stream = source().copy(clientResolve = StreamClientResolve(stream = StreamClientResolveStream(
            raw = StreamClientResolveRaw(folderSize = 80_000_000_000))))
        assertNull(BalancedAutoPlayPolicy.sizeBytes(stream))
    }
    @Test fun foreignAudioExcludedAndDualAudioAllowed() {
        assertFalse(BalancedAutoPlayPolicy.isEligible(source(languages = listOf("fr", "de"))))
        assertTrue(BalancedAutoPlayPolicy.isEligible(source(languages = listOf("fr", "eng"))))
    }
    @Test fun unknownAudioRequiresInspectionRatherThanInventedEnglish() {
        assertTrue(BalancedAutoPlayPolicy.isEligible(source(languages = listOf("und"))))
        assertFalse(BalancedAutoPlayPolicy.isEnglish("und"))
        assertFalse(BalancedAutoPlayPolicy.isEnglish("english subtitles"))
        assertTrue(BalancedAutoPlayPolicy.isEnglish("en_GB"))
        assertTrue(BalancedAutoPlayPolicy.isEnglish("English"))
    }
    @Test fun firstAndRegexModesCannotBypassRequirements() {
        val foreign = source(7_000_000_000, listOf("de"))
        val large = source(40_000_000_000, listOf("en"))
        val eligible = source(9_000_000_000, listOf("en"))
        for (mode in listOf(StreamAutoPlayMode.FIRST_STREAM, StreamAutoPlayMode.REGEX_MATCH)) {
            assertEquals(eligible, choose(listOf(foreign, large, eligible), mode).stream)
        }
    }
    @Test fun bingeGroupCannotBypassSizeCeiling() {
        val large = source(40_000_000_000).copy(behaviorHints = StreamBehaviorHints(videoSize = 40_000_000_000, bingeGroup = "same"))
        val small = source(8_000_000_000)
        assertEquals(small, choose(listOf(large, small), binge = "same").stream)
    }
    @Test fun noEligibleSourceReturnsPicker() {
        assertNull(choose(listOf(source(40_000_000_000), source(languages = listOf("de")))).stream)
    }
    @Test fun manualModeDoesNotAutoSelect() {
        assertNull(choose(listOf(source(8_000_000_000)), StreamAutoPlayMode.MANUAL).stream)
    }
    @Test fun bingeGroupRetriesStayWithinTheRequestedGroup() {
        val first = source(8_000_000_000).copy(behaviorHints = StreamBehaviorHints(videoSize = 8_000_000_000, bingeGroup = "same"))
        val second = first.copy(url = "https://example.invalid/second")
        val other = source(4_000_000_000)
        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(first, other, second), mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "", source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Test"), selectedAddons = emptySet(), selectedPlugins = emptySet(),
            preferredBingeGroup = "same", preferBingeGroupInSelection = true,
            bingeGroupOnly = true, balancedSelection = true)
        assertEquals(listOf(first, second), evaluation.readyStreams)
    }
}
