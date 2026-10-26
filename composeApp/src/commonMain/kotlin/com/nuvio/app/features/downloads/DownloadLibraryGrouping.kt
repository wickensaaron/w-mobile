package com.nuvio.app.features.downloads

internal data class DownloadShowGroup(val id: String, val title: String, val episodes: List<DownloadItem>)

internal val DownloadItem.downloadShowId: String
    get() = parentMetaId.trim().ifBlank { "title:${title.trim().lowercase()}" }

internal fun groupedDownloadShows(items: List<DownloadItem>): List<DownloadShowGroup> =
    items.filter { it.isEpisode }.groupBy { it.downloadShowId }.map { (id, entries) ->
        DownloadShowGroup(id, entries.first().title, entries.sortedForSeriesDownloads())
    }.sortedWith(compareBy<DownloadShowGroup> { it.title.trim().lowercase() }.thenBy { it.id })

internal fun groupedDownloadSeasons(items: List<DownloadItem>): List<Pair<Int, List<DownloadItem>>> =
    items.groupBy { it.seasonNumber ?: 0 }.toList().sortedBy { it.first }.map { (number, entries) -> number to entries.sortedForSeriesDownloads() }
