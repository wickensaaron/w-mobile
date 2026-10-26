package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadLibraryGroupingTest {
    private fun item(show: String, title: String, season: Int?, episode: Int?, status: DownloadStatus = DownloadStatus.Completed) = DownloadItem(
        id = "$show-$season-$episode", contentType = if (season == null) "movie" else "series", parentMetaId = show,
        parentMetaType = if (season == null) "movie" else "series", videoId = "$show:$season:$episode", title = title,
        seasonNumber = season, episodeNumber = episode, streamTitle = "Test", providerName = "Test",
        sourceUrl = "https://example.invalid/file.mkv", fileName = "$show-$season-$episode.mkv", status = status,
        createdAtEpochMs = 1, updatedAtEpochMs = 1,
    )

    @Test fun interleavedDownloadsFormOneShowWithSeasonsAndEpisodesInOrder() {
        val input = listOf(item("b", "Beta", 2, 4), item("a", "Alpha", 3, 2, DownloadStatus.Downloading),
            item("b", "Beta", 1, 8), item("a", "Alpha", 1, 1), item("b", "Beta", 1, 2))
        val groups = groupedDownloadShows(input)
        assertEquals(listOf("a", "b"), groups.map { it.id })
        assertEquals(listOf(1, 3), groupedDownloadSeasons(groups[0].episodes).map { it.first })
        assertEquals(listOf(2, 8), groupedDownloadSeasons(groups[1].episodes)[0].second.map { it.episodeNumber })
        assertEquals(5, groups.sumOf { it.episodes.size })
    }

    @Test fun moviesStaySeparateAndDifferentShowsWithTheSameTitleNeverMerge() {
        val groups = groupedDownloadShows(listOf(item("one", "Same title", 1, 1), item("two", "Same title", 1, 1), item("movie", "Movie", null, null)))
        assertEquals(listOf("one", "two"), groups.map { it.id })
        assertEquals(2, groups.sumOf { it.episodes.size })
    }

    @Test fun specialsAndActiveStatesRemainInsideTheirShowAndSeason() {
        val entries = listOf(item("show", "Show", 2, 1, DownloadStatus.Paused),
            item("show", "Show", 0, 2, DownloadStatus.Failed), item("show", "Show", 1, 3, DownloadStatus.Downloading))
        val group = groupedDownloadShows(entries).single()
        assertEquals(listOf(0, 1, 2), groupedDownloadSeasons(group.episodes).map { it.first })
        assertEquals(entries.map { it.id }.toSet(), group.episodes.map { it.id }.toSet())
    }
}
