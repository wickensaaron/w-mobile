package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class DownloadPlanningTest {
    private fun source(quality: String, size: Long = 100) = StreamItem(name = quality, url = "https://example.invalid/$quality.mkv",
        addonName = "Test", addonId = "test", behaviorHints = StreamBehaviorHints(videoSize = size))

    @Test fun qualityPresetsChooseAppropriateFilesAndPreferSmallerFilesAtTheSameQuality() {
        val p720 = source("720p", 80)
        val p1080 = source("1080p", 160)
        val compact1080 = source("1080p compact", 120)
        val p4k = source("2160p", 400)
        val all = listOf(p4k, p1080, compact1080, p720)
        assertSame(p720, chooseDownloadSource(all, DownloadQuality.Small))
        assertSame(compact1080, chooseDownloadSource(all, DownloadQuality.Balanced))
        assertSame(p4k, chooseDownloadSource(all, DownloadQuality.Best))
        assertSame(p1080, chooseDownloadSource(listOf(p1080, p4k), DownloadQuality.Small))
    }

    @Test fun automaticChoicesNeverDownloadAdaptiveCoreOrTorrentSources() {
        val safe = source("720p")
        assertSame(safe, chooseDownloadSource(listOf(source("2160p").copy(url = "https://example.invalid/live.m3u8"),
            source("1080p").copy(addonId = "wcore:source"), source("4k").copy(url = "magnet:?xt=test"), safe), DownloadQuality.Best))
        assertNull(chooseDownloadSource(emptyList(), DownloadQuality.Balanced))
        assertEquals(safe.copy(name = "unknown"), chooseDownloadSource(listOf(safe.copy(name = "unknown")), DownloadQuality.Best))
    }

    @Test fun wholeSeasonExcludesOtherSeasonsUnreleasedUnavailableAndDuplicateEpisodes() {
        val first = MetaVideo("s1e1", "One", released = "2026-01-01", season = 1, episode = 1)
        val second = first.copy(id = "s1e2", title = "Two", episode = 2)
        val all = listOf(second, first, first, first.copy(id = "other", season = 2),
            first.copy(id = "future", released = "2027-01-01"), first.copy(id = "unavailable", available = false))
        assertEquals(listOf(first, second), eligibleDownloadEpisodes(all, 1, "2026-10-22"))
    }

    @Test fun nextThreeSkipWatchedAndExistingDownloadsWithoutIncludingOtherEpisodes() {
        val season = (1..8).map { MetaVideo("ep$it", "Episode $it", season = 1, episode = it) }
        assertEquals(setOf("ep3", "ep5", "ep6"), nextUnwatchedDownloads(season, setOf("ep1", "ep4"), setOf("ep2")))
        assertEquals(emptySet(), nextUnwatchedDownloads(season, season.mapTo(mutableSetOf()) { it.id }, emptySet()))
    }
    @Test fun automaticDownloadRequiresConfirmedEnglishAudioAndTriesAnotherFile() = runTest {
        val wrong = source("1080p", 100)
        val english = source("1080p second", 200)
        val tried = mutableListOf<StreamItem>()
        assertSame(english, chooseEnglishDownloadSource(listOf(wrong, english), DownloadQuality.Balanced) {
            tried += it
            it === english
        })
        assertEquals(listOf(wrong, english), tried)
        assertNull(chooseEnglishDownloadSource(listOf(wrong), DownloadQuality.Small) { false })
    }

    @Test fun englishAudioFallbackCanUseAnotherResolutionWithoutChoosingAnUnverifiedFile() = runTest {
        val wrong = source("720p")
        val english = source("1080p")
        assertSame(english, chooseEnglishDownloadSource(listOf(wrong, english), DownloadQuality.Small) { it === english })
        var checked = 0
        assertNull(chooseEnglishDownloadSource((1..20).map { source("1080p $it") }, DownloadQuality.Best) { checked++; false })
        assertEquals(5, checked)
    }

    @Test fun audioCheckCancellationPropagatesAndLanguageMustBeExplicitEnglish() = runTest {
        assertFailsWith<CancellationException> {
            chooseEnglishDownloadSource(listOf(source("720p")), DownloadQuality.Small) { throw CancellationException("Closed") }
        }
        listOf("en", "eng", "English", "en-US", "en_GB").forEach { assertTrue(isEnglishDownloadLanguage(it)) }
        listOf(null, "und", "fr", "de", "multi", "enough").forEach { assertFalse(isEnglishDownloadLanguage(it)) }
    }

    @Test fun expandedSearchCanReachEnglishFilesBeyondTheInitialCandidateLimit() = runTest {
        val sources = (1..8).map { source("1080p $it", it.toLong()) }
        assertNull(chooseEnglishDownloadSource(sources, DownloadQuality.Balanced) { it === sources[7] })
        assertSame(sources[7], chooseEnglishDownloadSource(sources, DownloadQuality.Balanced, maxCandidates = 10) { it === sources[7] })
    }

    @Test fun englishHintsOnlyPrioritizeFilesAndStillRequireAudioVerification() = runTest {
        val foreign = source("1080p", 50)
        val hint = source("1080p ENG", 100)
        val tried = mutableListOf<StreamItem>()
        assertNull(chooseEnglishDownloadSource(listOf(foreign, hint), DownloadQuality.Balanced) { tried += it; false })
        assertEquals(listOf(hint, foreign), tried)
    }

    @Test fun unknownLanguageCanUseAnExplicitAudioTrackLabelWithoutOverridingForeignMetadata() {
        assertTrue(isEnglishDownloadAudio("und", "English 5.1"))
        assertTrue(isEnglishDownloadAudio(null, "ENG"))
        assertFalse(isEnglishDownloadAudio("fra", "English"))
        assertFalse(isEnglishDownloadAudio("und", "Track 1"))
    }

}
