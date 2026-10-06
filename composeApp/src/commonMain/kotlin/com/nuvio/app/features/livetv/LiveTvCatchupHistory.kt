package com.nuvio.app.features.livetv

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal const val MaxLiveTvArchiveHistoryBytes = 1024 * 1024
internal const val MaxLiveTvArchiveInfoBytes = 64 * 1024
internal class LiveTvArchiveSizeException : Exception("Catch-up response exceeds size limit")

/** Separate client has no app logging, credentials forwarding, redirects or automatic retries. */
internal interface LiveTvArchiveTransport {
    suspend fun timezone(selection: LiveTvCatchupSelection): ByteArray
    suspend fun history(selection: LiveTvCatchupSelection): ByteArray
}
private val sharedLiveTvArchiveTransport by lazy { LiveTvCatchupTransport() }
internal class LiveTvCatchupTransport(private val client: HttpClient = LiveTvArchivePlatform.client()) : LiveTvArchiveTransport {
    override suspend fun timezone(selection: LiveTvCatchupSelection): ByteArray =
        fetch(liveTvXtreamArchiveInfoUrl(selection), MaxLiveTvArchiveInfoBytes)
    override suspend fun history(selection: LiveTvCatchupSelection): ByteArray =
        fetch(liveTvXtreamHistoryUrl(selection), MaxLiveTvArchiveHistoryBytes)
    private suspend fun fetch(url: String?, limit: Int): ByteArray {
        require(url != null) { "Catch-up source unavailable" }
        return client.prepareGet(url) { header("Accept", "application/json"); header("User-Agent", "WMediaPlayer/1.0") }
            .execute { response ->
                check(response.status.value in 200..299) { "Catch-up source unavailable" }
                readLiveTvArchiveBytes(response.bodyAsChannel(), response.contentLength(), limit)
            }
    }
}

internal suspend fun readLiveTvArchiveBytes(channel: ByteReadChannel, contentLength: Long?, maxBytes: Int): ByteArray {
    require(maxBytes in 1..MaxLiveTvArchiveHistoryBytes)
    if (contentLength != null && contentLength > maxBytes) throw LiveTvArchiveSizeException()
    val chunks = mutableListOf<ByteArray>()
    var total = 0
    while (true) {
        coroutineContext.ensureActive()
        val buffer = ByteArray(minOf(8192, maxBytes - total + 1))
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count == -1) break
        if (count == 0) continue
        total += count
        if (total > maxBytes) throw LiveTvArchiveSizeException()
        chunks += buffer.copyOf(count)
    }
    return ByteArray(total).also { bytes ->
        var offset = 0
        chunks.forEach { it.copyInto(bytes, offset); offset += it.size }
    }
}

internal data class LiveTvArchiveTimezone(val zone: String?, val status: LiveTvArchiveStatus)
private fun JsonObject.field(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull?.trim()

internal fun parseLiveTvArchiveTimezone(bytes: ByteArray, clock: LiveTvArchiveClock): LiveTvArchiveTimezone {
    if (bytes.size > MaxLiveTvArchiveInfoBytes) return LiveTvArchiveTimezone(null, LiveTvArchiveStatus.RESPONSE_TOO_LARGE)
    return try {
        val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
            ?: return LiveTvArchiveTimezone(null, LiveTvArchiveStatus.INVALID_RESPONSE)
        val user = root["user_info"] as? JsonObject
            ?: return LiveTvArchiveTimezone(null, LiveTvArchiveStatus.FETCH_FAILED)
        if (user.field("auth") != "1" || user.field("status")?.let { it != "Active" } == true)
            return LiveTvArchiveTimezone(null, LiveTvArchiveStatus.FETCH_FAILED)
        val server = root["server_info"] as? JsonObject
            ?: return LiveTvArchiveTimezone(null, LiveTvArchiveStatus.UNKNOWN_TIMEZONE)
        val zone = server.field("timezone")?.takeIf(String::isNotBlank)
            ?: return LiveTvArchiveTimezone(null, LiveTvArchiveStatus.UNKNOWN_TIMEZONE)
        if (!clock.validZone(zone) || archiveClockDisagrees(server.field("timestamp_now"), server.field("time_now"), zone, clock))
            LiveTvArchiveTimezone(null, LiveTvArchiveStatus.INVALID_TIMEZONE)
        else LiveTvArchiveTimezone(zone, LiveTvArchiveStatus.SUCCESS)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { LiveTvArchiveTimezone(null, LiveTvArchiveStatus.INVALID_RESPONSE) }
}

/** Conflicting absolute and wall time fields cannot safely identify an archive recording. */
private fun archiveClockDisagrees(epoch: String?, local: String?, zone: String, clock: LiveTvArchiveClock): Boolean {
    if (epoch.isNullOrBlank() || local.isNullOrBlank()) return false
    val absolute = clock.timestamp(epoch, zone) ?: return true
    val wall = clock.timestamp(local, zone) ?: return true
    return kotlin.math.abs(absolute - wall) > 60_000L
}

@OptIn(ExperimentalEncodingApi::class)
private fun archiveTitle(value: String?): String? {
    if (value.isNullOrBlank() || value.length > 2048) return null
    val decoded = runCatching { Base64.Default.decode(value).decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    val title = decoded ?: if ('=' !in value) value else return null
    if (title.length > 512 || Regex("https?://|(?:password|username|token)=", RegexOption.IGNORE_CASE).containsMatchIn(title)) return null
    return title.map { if (it.code < 32 || it.code == 127) ' ' else it }.joinToString("")
        .replace(Regex("\\s+"), " ").trim().takeIf(String::isNotBlank)
}

internal fun parseLiveTvArchiveHistory(bytes: ByteArray, selection: LiveTvCatchupSelection,
    zone: String, day: String, clock: LiveTvArchiveClock, nowMs: Long): LiveTvArchiveHistory {
    fun result(status: LiveTvArchiveStatus, programmes: List<LiveTvArchiveProgramme> = emptyList(), airing: LiveTvArchiveProgramme? = null) =
        LiveTvArchiveHistory(selection, zone, day, status, programmes, airing)
    if (bytes.size > MaxLiveTvArchiveHistoryBytes) return result(LiveTvArchiveStatus.RESPONSE_TOO_LARGE)
    if (!clock.validZone(zone)) return result(LiveTvArchiveStatus.INVALID_TIMEZONE)
    val retention = selection.archive.retentionDays ?: return result(LiveTvArchiveStatus.UNKNOWN_RETENTION)
    val bounds = clock.dayBounds(day, zone) ?: return result(LiveTvArchiveStatus.INVALID_TIME)
    if (nowMs !in 0..253_402_300_799_999L || bounds.first > nowMs || bounds.second <= nowMs - retention.coerceAtMost(30) * 86_400_000L)
        return result(LiveTvArchiveStatus.OUTSIDE_WINDOW)
    return try {
        val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
            ?: return result(LiveTvArchiveStatus.INVALID_RESPONSE)
        val listings = root["epg_listings"] as? JsonArray ?: return result(LiveTvArchiveStatus.INVALID_RESPONSE)
        if (listings.size > 4096) return result(LiveTvArchiveStatus.RESPONSE_TOO_LARGE)
        val programmes = linkedSetOf<LiveTvArchiveProgramme>()
        val airing = linkedSetOf<LiveTvArchiveProgramme>()
        for (element in listings) {
            val row = element as? JsonObject ?: continue
            if ("has_archive" in row && row.field("has_archive") != "1") continue
            if (row.field("stream_id")?.let { it != selection.streamId } == true) continue
            val start = clock.timestamp(row.field("start_timestamp")?.takeIf(String::isNotBlank) ?: row.field("start") ?: continue, zone) ?: continue
            val end = clock.timestamp(row.field("stop_timestamp")?.takeIf(String::isNotBlank) ?: row.field("end") ?: continue, zone) ?: continue
            if (start >= bounds.second || end <= bounds.first) continue
            if (archiveClockDisagrees(row.field("start_timestamp"), row.field("start"), zone, clock) ||
                archiveClockDisagrees(row.field("stop_timestamp"), row.field("end"), zone, clock)) return result(LiveTvArchiveStatus.INVALID_RESPONSE)
            val title = archiveTitle(row.field("title")) ?: continue
            val programme = LiveTvArchiveProgramme(selection.channelId, title, start, end, row.field("has_archive") == "1")
            if (liveTvArchiveEligibility(selection.archive, start, end, nowMs) == LiveTvArchiveStatus.SUCCESS) programmes += programme
            if (programme.recordingAdvertised && start < nowMs && end > nowMs &&
                liveTvArchiveEligibility(selection.archive, start, nowMs / 60_000 * 60_000, nowMs) == LiveTvArchiveStatus.SUCCESS) airing += programme
        }
        val current = airing.singleOrNull()
        val sorted = programmes.sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.title }))
        result(if (sorted.isEmpty() && current == null) LiveTvArchiveStatus.NO_PROGRAMMES else LiveTvArchiveStatus.SUCCESS, sorted, current)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { result(LiveTvArchiveStatus.INVALID_RESPONSE) }
}

/** No history is merged into the live guide. Ownership is checked on both sides of every await. */
internal class LiveTvCatchupLoader(private val clock: LiveTvArchiveClock,
    private val transport: LiveTvArchiveTransport = sharedLiveTvArchiveTransport) {
    suspend fun timezone(selection: LiveTvCatchupSelection, owns: () -> Boolean): LiveTvArchiveTimezone {
        if (!owns()) return LiveTvArchiveTimezone(null, LiveTvArchiveStatus.STALE_SELECTION)
        return try {
            val bytes = transport.timezone(selection)
            if (owns()) parseLiveTvArchiveTimezone(bytes, clock) else LiveTvArchiveTimezone(null, LiveTvArchiveStatus.STALE_SELECTION)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: LiveTvArchiveSizeException) { LiveTvArchiveTimezone(null, LiveTvArchiveStatus.RESPONSE_TOO_LARGE) }
        catch (_: Exception) { LiveTvArchiveTimezone(null, LiveTvArchiveStatus.FETCH_FAILED) }
    }
    suspend fun history(selection: LiveTvCatchupSelection, zone: String, day: String, nowMs: Long,
        owns: () -> Boolean): LiveTvArchiveHistory {
        fun failure(status: LiveTvArchiveStatus) = LiveTvArchiveHistory(selection, zone, day, status)
        if (!owns()) return failure(LiveTvArchiveStatus.STALE_SELECTION)
        if (selection.archive.retentionDays == null) return failure(LiveTvArchiveStatus.UNKNOWN_RETENTION)
        if (!clock.validZone(zone)) return failure(LiveTvArchiveStatus.INVALID_TIMEZONE)
        if (day !in clock.days(nowMs, selection.archive.retentionDays, zone)) return failure(LiveTvArchiveStatus.OUTSIDE_WINDOW)
        return try {
            val bytes = transport.history(selection)
            if (owns()) parseLiveTvArchiveHistory(bytes, selection, zone, day, clock, nowMs) else failure(LiveTvArchiveStatus.STALE_SELECTION)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: LiveTvArchiveSizeException) { failure(LiveTvArchiveStatus.RESPONSE_TOO_LARGE) }
        catch (_: Exception) { failure(LiveTvArchiveStatus.FETCH_FAILED) }
    }
}

internal fun liveTvArchiveMessage(status: LiveTvArchiveStatus): String = when (status) {
    LiveTvArchiveStatus.SUCCESS -> "Choose a programme to replay from the beginning."
    LiveTvArchiveStatus.NO_PROGRAMMES -> "No recorded programmes for this day. Try another day."
    LiveTvArchiveStatus.UNSUPPORTED -> "This channel does not advertise catch-up."
    LiveTvArchiveStatus.UNKNOWN_RETENTION -> "This source has not supplied the channel's recording window."
    LiveTvArchiveStatus.UNKNOWN_TIMEZONE, LiveTvArchiveStatus.INVALID_TIMEZONE -> "This source has not supplied a valid archive timezone."
    LiveTvArchiveStatus.STALE_SELECTION -> "The account or source changed. Choose the channel again."
    LiveTvArchiveStatus.OUTSIDE_WINDOW, LiveTvArchiveStatus.EXPIRED -> "This programme is outside the recording window."
    LiveTvArchiveStatus.NOT_FINISHED -> "This programme has not finished yet."
    LiveTvArchiveStatus.TOO_LONG -> "This recording is longer than the supported twelve-hour window."
    LiveTvArchiveStatus.INVALID_TIME, LiveTvArchiveStatus.INVALID_RESPONSE, LiveTvArchiveStatus.RESPONSE_TOO_LARGE -> "The source returned an unusable catch-up guide."
    LiveTvArchiveStatus.FETCH_FAILED -> "Could not load catch-up. Try again."
}
