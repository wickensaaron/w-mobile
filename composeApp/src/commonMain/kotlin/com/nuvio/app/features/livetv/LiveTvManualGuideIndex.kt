package com.nuvio.app.features.livetv

/**
 * One index per immutable guide snapshot. Imported account IDs may be excluded by the caller,
 * and imported channels never use this global manual-guide lookup.
 */
internal class LiveTvManualGuideIndex(
    programmes: Map<String, List<LiveTvProgramme>>,
    excludedChannelIds: Set<String> = emptySet(),
) {
    private val exact = programmes.filterKeys { it !in excludedChannelIds }
    private val insensitive = linkedMapOf<String, List<LiveTvProgramme>>()

    init {
        for ((key, rows) in exact) {
            val normalized = manualGuideCaseKey(key)
            if (normalized !in insensitive) insensitive[normalized] = rows
        }
    }

    fun programmesFor(channel: LiveTvChannel): List<LiveTvProgramme> {
        if (channel.accountScope != null) return emptyList()
        val key = channel.guideId ?: channel.name
        return exact[key] ?: insensitive[manualGuideCaseKey(key)].orEmpty()
    }

    fun hasUnexpiredGuide(channel: LiveTvChannel, nowMs: Long): Boolean = programmesFor(channel).any {
        it.title.isNotBlank() && it.stopEpochMs > nowMs && it.stopEpochMs > it.startEpochMs
    }
}

/** Match equals(ignoreCase=true), including one-character Unicode case variants. */
private fun manualGuideCaseKey(value: String): String = buildString(value.length) {
    value.forEach { append(it.uppercaseChar().lowercaseChar()) }
}
