package com.nuvio.app.features.livetv

/** Keep raw source indices and duplicate streams: those are desktop/TV playback identities. */
internal fun parseImportedLiveTvPlaylist(payload: String, source: ImportedLiveTvSource,
    checkActive: () -> Unit = {}): List<LiveTvChannel> {
    checkActive()
    require(payload.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")) { "Invalid account playlist" }
    val attribute = Regex("([\\w-]+)\\s*=\\s*\"([^\"]*)\"")
    val channels = mutableListOf<LiveTvChannel>()
    var name = ""
    var attributes = emptyMap<String, String>()
    var groupOverride = ""
    for ((index, raw) in payload.lineSequence().withIndex()) {
        if (index % 256 == 0) checkActive()
        val line = raw.trim()
        when {
            line.startsWith("#EXTINF:", true) -> {
                attributes = attribute.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                var quoted = false
                val separator = line.indexOfFirst {
                    if (it == '"') quoted = !quoted
                    it == ',' && !quoted
                }
                name = if (separator < 0) "" else line.substring(separator + 1).trim()
                if (name.isBlank()) name = attributes["tvg-name"].orEmpty()
                groupOverride = ""
            }
            line.startsWith("#EXTGRP:", true) -> groupOverride = line.substringAfter(':').trim()
            line.startsWith("http://") || line.startsWith("https://") -> {
                // An unnamed HTTP row is ambiguous: desktop inserts a fallback while TV skips it.
                // Reject that source rather than binding the next named channel to a different index.
                require(name.isNotBlank()) { "Account playlist contains an unnamed channel" }
                require(channels.size < MobileLiveTvCatalogueLimit) { "Account playlist exceeds device limit" }
                channels += LiveTvChannel("${source.id}:${channels.size}", name, line,
                    logoUrl = attributes["tvg-logo"], group = groupOverride.ifBlank { attributes["group-title"].orEmpty() }.ifBlank { "Other" },
                    playlistId = source.id, playlistName = source.name,
                    guideId = attributes["tvg-id"].orEmpty().ifBlank { name })
                name = ""
                attributes = emptyMap()
                groupOverride = ""
            }
            line.isBlank() || line.startsWith("#") -> Unit
            else -> {
                name = ""
                attributes = emptyMap()
                groupOverride = ""
                require(false) { "Account playlist contains an unsupported media address" }
            }
        }
    }
    checkActive()
    return channels.toList()
}
