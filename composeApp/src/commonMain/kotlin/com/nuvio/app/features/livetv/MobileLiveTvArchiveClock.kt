package com.nuvio.app.features.livetv

import kotlinx.datetime.*
import kotlin.time.Instant

/** Provider wall time is resolved strictly; DST gaps and overlaps never silently move a recording. */
internal object MobileLiveTvArchiveClock : LiveTvArchiveClock {
    override fun validZone(zone: String): Boolean = zone.isNotBlank() && zone.length <= 80 &&
        zone.none { it.isISOControl() } && runCatching { TimeZone.of(zone) }.isSuccess

    override fun timestamp(value: String, zone: String): Long? {
        if (!validZone(zone) || value.length > 40) return null
        value.toLongOrNull()?.let { return it.takeIf { n -> n in 0..253_402_300_799L }?.times(1000) }
        runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()?.let {
            return it.takeIf { n -> n in 0..253_402_300_799_999L }
        }
        val wall = runCatching { LocalDateTime.parse(value.replace(' ', 'T')) }.getOrNull() ?: return null
        val tz = TimeZone.of(zone)
        val nominal = wall.toInstant(TimeZone.UTC).toEpochMilliseconds()
        // Collect offsets on both sides of even large historical transitions; verify exact round trips.
        val candidates = (-48..48 step 3).map { hours ->
            tz.offsetAt(Instant.fromEpochMilliseconds(nominal + hours * 3_600_000L)).totalSeconds
        }.distinct().map { offset -> nominal - offset * 1000L }.filter {
            it >= 0 && Instant.fromEpochMilliseconds(it).toLocalDateTime(tz) == wall
        }
        return candidates.singleOrNull()
    }

    override fun localTimestamp(epochMs: Long, zone: String): String {
        val value = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.of(zone))
        return "${value.date} ${value.hour.toString().padStart(2, '0')}:${value.minute.toString().padStart(2, '0')}:${value.second.toString().padStart(2, '0')}"
    }

    override fun archiveStart(epochMs: Long, zone: String): String {
        val wall = localTimestamp(epochMs / 60_000 * 60_000, zone)
        require(timestamp(wall, zone) == epochMs / 60_000 * 60_000) { "Ambiguous archive start" }
        return wall.take(16).replace(' ', ':').let { it.take(13) + '-' + it.takeLast(2) }
    }

    override fun dayBounds(day: String, zone: String): Pair<Long, Long>? = runCatching {
        val date = LocalDate.parse(day)
        val tz = TimeZone.of(zone)
        date.atStartOfDayIn(tz).toEpochMilliseconds() to date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz).toEpochMilliseconds()
    }.getOrNull()

    override fun days(nowMs: Long, retentionDays: Int, zone: String): List<String> = runCatching {
        if (retentionDays < 1 || nowMs !in 0..253_402_300_799_999L) return emptyList()
        val tz = TimeZone.of(zone)
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date
        val first = Instant.fromEpochMilliseconds((nowMs - retentionDays.coerceAtMost(30) * 86_400_000L).coerceAtLeast(0)).toLocalDateTime(tz).date
        (0..30).map { today.minus(it, DateTimeUnit.DAY) }.takeWhile { it >= first }.map { it.toString() }
    }.getOrDefault(emptyList())
}

internal data class MobileLiveTvArchiveSource(
    val id: String,
    val syncId: String,
    val name: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val epgUrl: String = "",
) {
    override fun toString() = "MobileLiveTvArchiveSource(captured)"
}
