package com.nuvio.app.features.livetv

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

internal data class LiveTvGuideProjectionResult(
    val programmes: Map<String, List<LiveTvProgramme>>,
    val programmeCount: Int,
    val wasTruncated: Boolean,
)

/** Confined to one worker coroutine; budgets are checked before programme copies are allocated. */
internal class LiveTvGuideProjection(private val limit: Int = MobileLiveTvProgrammeLimit) {
    private val programmes = linkedMapOf<String, List<LiveTvProgramme>>()
    private var programmeCount = 0
    private var operations = 0
    private var wasTruncated = false

    init { require(limit >= 0) }

    val hasCapacity: Boolean get() = programmeCount < limit

    suspend fun appendManual(parsed: Map<String, List<LiveTvProgramme>>) {
        checkpoint()
        for ((key, rows) in parsed) {
            checkpoint()
            if (rows.isEmpty()) continue
            if (!hasCapacity) {
                wasTruncated = true
                break
            }
            appendRows(key, rows, ownedChannelId = null)
        }
    }

    suspend fun appendImported(sourceId: String, channels: List<LiveTvChannel>, parsed: Map<String, List<LiveTvProgramme>>) {
        checkpoint()
        // Exact spelling wins. Case-insensitive collisions retain the first XMLTV entry, as before.
        val insensitive = mutableMapOf<String, List<LiveTvProgramme>>()
        for ((key, rows) in parsed) {
            checkpoint()
            val normalized = caseInsensitiveGuideKey(key)
            if (normalized !in insensitive) insensitive[normalized] = rows
        }
        for (channel in channels) {
            checkpoint()
            if (channel.accountScope == null || channel.playlistId != sourceId) continue
            val key = channel.guideId ?: channel.name
            val rows = parsed[key] ?: insensitive[caseInsensitiveGuideKey(key)] ?: continue
            if (rows.isEmpty()) continue
            if (!hasCapacity) {
                wasTruncated = true
                break
            }
            appendRows(channel.id, rows, ownedChannelId = channel.id)
        }
    }

    private suspend fun appendRows(key: String, rows: List<LiveTvProgramme>, ownedChannelId: String?) {
        val acceptedCount = minOf(rows.size, limit - programmeCount)
        if (acceptedCount != rows.size) wasTruncated = true
        if (acceptedCount == 0) return
        val accepted = ArrayList<LiveTvProgramme>(acceptedCount)
        for (index in 0 until acceptedCount) {
            checkpoint()
            val row = rows[index]
            accepted += if (ownedChannelId == null) row else row.copy(channelId = ownedChannelId)
        }
        programmes[key] = accepted.toList()
        programmeCount += acceptedCount
    }

    suspend fun snapshot(): LiveTvGuideProjectionResult {
        currentCoroutineContext().ensureActive()
        return LiveTvGuideProjectionResult(programmes.toMap(), programmeCount, wasTruncated)
    }

    private suspend fun checkpoint() {
        if (operations++ % 256 == 0) {
            currentCoroutineContext().ensureActive()
            yield()
        }
    }

    private fun caseInsensitiveGuideKey(key: String): String = buildString(key.length) {
        // Match String.equals(ignoreCase = true), including one-character Unicode case variants.
        key.forEach { append(it.uppercaseChar().lowercaseChar()) }
    }
}
