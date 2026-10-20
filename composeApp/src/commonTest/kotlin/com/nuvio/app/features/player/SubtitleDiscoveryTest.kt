package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.details.MetaDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleDiscoveryTest {
    private val movie = MetaDetails("tmdb:123", "movie", "Film", imdbId = "tt1234567")
    private val series = MetaDetails("wcore:series", "series", "Show", imdbId = "tt7654321")

    @Test fun catalogueMovieUsesItsPublicImdbIdentity() {
        assertEquals("tt1234567", subtitleDiscoveryVideoId(movie.id, "movie", movie.id, movie, null, null))
    }

    @Test fun libraryEpisodeUsesSeriesImdbAndExactEpisode() {
        assertEquals("tt7654321:2:3", subtitleDiscoveryVideoId("wcore:episode", "series", series.id, series, 2, 3))
        assertEquals("tt7654321:0:1", subtitleDiscoveryVideoId("wcore:special", "tv", series.id, series, 0, 1))
    }

    @Test fun existingImdbEpisodeIsNotReplacedByMetadata() {
        assertEquals("tt1111111:1:4", subtitleDiscoveryVideoId("tt1111111:1:4", "series", series.id, series, 2, 3))
    }

    @Test fun missingEpisodeCoordinatesNeverSearchTheWholeShow() {
        assertEquals("wcore:episode", subtitleDiscoveryVideoId("wcore:episode", "series", series.id, series, null, 3))
        assertEquals("wcore:episode", subtitleDiscoveryVideoId("wcore:episode", "series", series.id, series, 1, 0))
        assertNull(subtitleDiscoveryVideoId("tt7654321", "series", "tt7654321", null, null, null))
    }

    @Test fun lateMetadataChangesDiscoveryIdentityWithoutChangingPlayback() {
        assertEquals(movie.id, subtitleDiscoveryVideoId(movie.id, "movie", movie.id, null, null, null))
        assertEquals("tt1234567", subtitleDiscoveryVideoId(movie.id, "movie", movie.id, movie, null, null))
        assertEquals("tt1234567", subtitleDiscoveryVideoId(null, "movie", movie.id, movie, null, null))
    }

    @Test fun staleOrWrongTypeMetadataCannotSupplyAnotherItemsSubtitles() {
        assertEquals("tmdb:other", subtitleDiscoveryVideoId("tmdb:other", "movie", "tmdb:other", movie, null, null))
        assertEquals(movie.id, subtitleDiscoveryVideoId(movie.id, "movie", movie.id, movie.copy(type = "series"), null, null))
    }

    @Test fun parentImdbCanSupplyMovieOrEpisodeIdentity() {
        assertEquals("tt1234567", subtitleDiscoveryVideoId(null, "movie", "tt1234567", null, null, null))
        assertEquals("tt1234567:1:2", subtitleDiscoveryVideoId("episode:2", "series", "tt1234567", null, 1, 2))
        assertNull(subtitleDiscoveryVideoId(null, "movie", "unknown", null, null, null))
    }

    @Test fun tvResourceAndSeriesRequestUseTheSameCompatibilityRule() {
        val resource = AddonResource("subtitles", listOf("tv"), listOf("tt"))
        assertTrue(resource.isCompatibleSubtitleResource("series", "tt1234567:1:2"))
        assertFalse(resource.isCompatibleSubtitleResource("movie", "tt1234567"))
        assertFalse(resource.isCompatibleSubtitleResource("series", "wcore:episode"))
    }


}
