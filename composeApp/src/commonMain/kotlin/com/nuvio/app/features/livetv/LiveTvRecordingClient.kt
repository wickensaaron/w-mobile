package com.nuvio.app.features.livetv

import com.nuvio.app.core.network.WCoreConnectionRepository
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.userId
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import com.nuvio.app.features.addons.httpRequestRaw
import io.ktor.http.formUrlEncode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class LiveTvRecording(
    val id: String,
    val title: String,
    val channelName: String,
    val streamId: String? = null,
    val guideId: String? = null,
    val requestedStreamId: String? = null,
    val requestedChannelName: String? = null,
    val sourceFallbackUsed: Boolean = false,
    val startMs: Long,
    val endMs: Long,
    val recordFromMs: Long? = null,
    val status: String,
    val protectSport: Boolean,
    val beforeMinutes: Int = 0,
    val afterMinutes: Int,
    val errorCode: String?,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val artworkUrl: String? = null,
)

internal fun recordingFailureMessage(code: String?): String = when (code) {
    "recording_stream_host_not_allowed" -> "W Core blocked this channel's stream host. Check the approved host list."
    "recording_stream_format_unsupported" -> "This channel did not provide a valid MPEG-TS stream."
    "recording_stream_unavailable", "recording_stream_empty", "recording_stream_ended",
    "recording_stream_stalled" -> "The IPTV stream stopped or could not be reached."
    "recording_video_corrupt" -> "W Core rejected corrupted video from every matching channel source."
    "recording_video_unusable" -> "W Core could not decode usable video from a matching channel source."
    "recording_video_probe_unavailable" -> "W Core's recording quality check is unavailable."
    "recording_storage_low", "recording_size_limit" -> "Recording stopped because storage ran low or its size limit was reached."
    "recording_worker_not_leader" -> "W Core lost recording ownership. Check the server status."
    else -> "Recording failed. Check W Core status before trying again."
}

internal fun recordingProgrammeStartOffsetMs(recording: LiveTvRecording): Long? {
    if (recording.status != "ready" || recording.endMs <= recording.startMs) return null
    val plannedStart = recording.recordFromMs
        ?: recording.beforeMinutes.takeIf { it > 0 }?.let { recording.startMs - it * 60_000L }
        ?: return null
    return (recording.startMs - plannedStart).takeIf { it in 1..3_600_000L }
}

internal class LiveTvRecordingException(message: String) : Exception(message)

internal data class CoreRecordingProvider(val id: String, val name: String, val programmeCount: Int)
internal data class CoreRecordingProgramme(val title: String, val startMs: Long, val endMs: Long)
internal data class CoreRecordingGuide(
    val guideId: String,
    val programmes: List<CoreRecordingProgramme>,
    val unavailableReason: String? = null,
)

internal fun recordingGuideUnavailableMessage(reason: String?): String = when (reason) {
    "no_guide_id" -> "This channel has no guide ID in your IPTV provider. Scheduled recording is unavailable for this feed."
    "stale_provider_guide" -> "W Core's TV guide is out of date. Its provider guide needs to refresh before you can schedule this programme."
    "provider_guide_unavailable" -> "W Core could not load this provider's TV guide. Scheduled recording is unavailable until the guide recovers."
    "no_upcoming_programmes" -> "W Core has no upcoming programmes for this channel yet. Try again after its guide updates."
    else -> "W Core has no upcoming guide data for this feed. Scheduling is unavailable until its guide updates."
}
internal data class CoreRecordingSeriesRule(
    val id: String, val programmeTitle: String, val channelName: String, val lastError: String?,
)
internal data class CoreRecordingAlternative(val streamId: String, val channelName: String, val guideId: String)

internal fun recordingArtworkLookupTitle(recording: LiveTvRecording): String? {
    val title = recording.seriesTitle?.trim()?.takeIf(String::isNotBlank)
        ?: recording.title.trim()
    if (title.startsWith("Record Now:", ignoreCase = true)) return null
    return title.takeIf { it.length in 2..240 }
}

internal fun normalizedRecordingTitle(value: String): String = value.trim().lowercase()
    .replace(Regex("[^a-z0-9]+"), " ").trim()

internal enum class RecordNowMode(val apiValue: String, val label: String) {
    ThirtyMinutes("thirty_minutes", "30 minutes"),
    OneHour("one_hour", "1 hour"),
    ProgrammeEnd("programme_end", "Until programme ends"),
    UntilStopped("until_stopped", "Until stopped (6 hour limit)"),
}

private const val MaxRecordNowDurationMs = 6 * 60 * 60 * 1000L

internal fun recordNowProgramme(mode: RecordNowMode, programme: LiveTvProgramme?, nowMs: Long): LiveTvProgramme? {
    val current = programme?.takeIf { it.startEpochMs <= nowMs && it.stopEpochMs > nowMs &&
        it.stopEpochMs - nowMs <= MaxRecordNowDurationMs }
    if (mode == RecordNowMode.ProgrammeEnd && current == null) {
        throw LiveTvRecordingException("Choose a current guide programme for programme end.")
    }
    return current
}

internal fun recordingStreamId(channel: LiveTvChannel, isXtreamSource: Boolean): String {
    val prefix = if (channel.accountScope != null) "${channel.playlistId}:" else "xtream:"
    val streamId = channel.id.removePrefix(prefix)
    if (!isXtreamSource || !channel.id.startsWith(prefix) || !streamId.matches(Regex("[0-9]{1,20}")) ||
        channel.guideId.isNullOrBlank()) {
        throw LiveTvRecordingException("This channel needs an exact Xtream stream and guide ID to record.")
    }
    return streamId
}

internal fun exactXtreamRecordingStreamId(channel: LiveTvChannel, programme: LiveTvProgramme? = null): String {
    if (!LiveTvRepository.isCurrentPlaybackRequest(channel)) {
        throw LiveTvRecordingException("Your TV source changed. Reopen the guide and try again.")
    }
    val state = LiveTvRepository.uiState.value
    val isXtream = if (channel.accountScope != null) state.accountSources.any {
        it.id == channel.playlistId && it.type == "XTREAM" && it.enabled
    } else channel.playlistId == "provider:xtream" && state.xtreamSettings.isConfigured && state.xtreamSettings.isEnabled
    // Imported guide rows are indexed by the owned channel ID, local XMLTV by guide ID.
    if (programme != null && programme.channelId != channel.id &&
        !programme.channelId.equals(channel.guideId, ignoreCase = true)) {
        throw LiveTvRecordingException("This programme no longer belongs to the selected channel.")
    }
    return recordingStreamId(channel, isXtream)
}

internal class RecordingRequestOwner(val profileId: Int, private val channel: LiveTvChannel? = null) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RecordingRequestOwner>
    private val accountId = AuthRepository.state.value.userId
    private val session = WCoreConnectionRepository.currentConnection()
    private val revision = WCoreConnectionRepository.connectionRevision()
    fun isCurrent(): Boolean = session != null && accountId != null &&
        accountId == AuthRepository.state.value.userId && profileId == ProfileRepository.activeProfileId &&
        session == WCoreConnectionRepository.currentConnection() && revision == WCoreConnectionRepository.connectionRevision() &&
        (channel == null || LiveTvRepository.isCurrentPlaybackRequest(channel))
}

private suspend fun ensureRecordingRequestOwner() {
    currentCoroutineContext().ensureActive()
    if (currentCoroutineContext()[RecordingRequestOwner]?.isCurrent() != true) {
        throw LiveTvRecordingException("Your account or profile changed. Reopen recordings and try again.")
    }
}

private suspend fun <T> recordingRequest(profileId: Int, channel: LiveTvChannel? = null, block: suspend () -> T): T =
    withContext(RecordingRequestOwner(profileId, channel)) {
        ensureRecordingRequestOwner()
        block().also { ensureRecordingRequestOwner() }
    }

internal object LiveTvRecordingClient {
    suspend fun list(profileId: Int) = recordingRequest(profileId) { LiveTvRecordingApi.list(profileId) }
    suspend fun listSeries(profileId: Int) = recordingRequest(profileId) { LiveTvRecordingApi.listSeries(profileId) }
    suspend fun deleteSeries(profileId: Int, id: String) = recordingRequest(profileId) { LiveTvRecordingApi.deleteSeries(profileId, id) }
    suspend fun coreProviders() = recordingRequest(ProfileRepository.activeProfileId) { LiveTvRecordingApi.coreProviders() }
    suspend fun coreGuide(providerId: String, streamId: String) = recordingRequest(ProfileRepository.activeProfileId) { LiveTvRecordingApi.coreGuide(providerId, streamId) }
    suspend fun alternatives(providerId: String, streamId: String) = recordingRequest(ProfileRepository.activeProfileId) { LiveTvRecordingApi.alternatives(providerId, streamId) }
    suspend fun artworkForProgramme(title: String) = recordingRequest(ProfileRepository.activeProfileId) { LiveTvRecordingApi.artworkForProgramme(title) }
    suspend fun schedule(profileId: Int, channel: LiveTvChannel, programme: LiveTvProgramme, protectSport: Boolean, coreProviderId: String) =
        recordingRequest(profileId, channel) { LiveTvRecordingApi.schedule(profileId, channel, programme, protectSport, coreProviderId) }
    suspend fun createSeries(profileId: Int, channel: LiveTvChannel, programme: LiveTvProgramme, coreProviderId: String) =
        recordingRequest(profileId, channel) { LiveTvRecordingApi.createSeries(profileId, channel, programme, coreProviderId) }
    suspend fun recordNow(profileId: Int, channel: LiveTvChannel, programme: LiveTvProgramme?, mode: RecordNowMode, coreProviderId: String, nowMs: Long) =
        recordingRequest(profileId, channel) { LiveTvRecordingApi.recordNow(profileId, channel, programme, mode, coreProviderId, nowMs) }
    suspend fun cancel(profileId: Int, id: String) = recordingRequest(profileId) { LiveTvRecordingApi.cancel(profileId, id) }
    suspend fun delete(profileId: Int, id: String) = recordingRequest(profileId) { LiveTvRecordingApi.delete(profileId, id) }
    suspend fun extend(profileId: Int, id: String) = recordingRequest(profileId) { LiveTvRecordingApi.extend(profileId, id) }
    suspend fun playbackUrl(profileId: Int, id: String) = recordingRequest(profileId) {
        val owner = requireNotNull(currentCoroutineContext()[RecordingRequestOwner])
        LiveTvRecordingApi.playbackUrl(profileId, id).also { rememberIssuedRecordingPlaybackUrl(id, it, owner) }
    }
}

internal fun decodeLiveTvRecording(value: JsonObject): LiveTvRecording? {
        val programme = value["programme"] as? JsonObject ?: return null
        val channel = value["channel"] as? JsonObject ?: return null
        return LiveTvRecording(
            id = value["id"]?.jsonPrimitive?.contentOrNull ?: return null,
            title = programme["title"]?.jsonPrimitive?.contentOrNull ?: return null,
            channelName = channel["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            streamId = value["streamId"]?.jsonPrimitive?.contentOrNull,
            guideId = channel["guideId"]?.jsonPrimitive?.contentOrNull,
            requestedStreamId = value["requestedStreamId"]?.jsonPrimitive?.contentOrNull,
            requestedChannelName = value["requestedChannelName"]?.jsonPrimitive?.contentOrNull,
            sourceFallbackUsed = value["sourceFallbackUsed"]?.jsonPrimitive?.content == "true",
            startMs = programme["startMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null,
            endMs = programme["endMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null,
            recordFromMs = value["recordFromMs"]?.jsonPrimitive?.content?.toLongOrNull(),
            status = value["status"]?.jsonPrimitive?.contentOrNull ?: return null,
            protectSport = value["protectSport"]?.jsonPrimitive?.content == "true",
            beforeMinutes = value["beforeMinutes"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            afterMinutes = value["afterMinutes"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            errorCode = value["error"]?.jsonPrimitive?.contentOrNull,
            seriesTitle = programme["seriesTitle"]?.jsonPrimitive?.contentOrNull,
            seasonNumber = programme["seasonNumber"]?.jsonPrimitive?.content?.toIntOrNull(),
            episodeNumber = programme["episodeNumber"]?.jsonPrimitive?.content?.toIntOrNull(),
            episodeTitle = programme["episodeTitle"]?.jsonPrimitive?.contentOrNull,
        )
    }


private object LiveTvRecordingApi {
    private suspend fun selectedCoreProfileId(): String {
        ensureRecordingRequestOwner()
        val session = WCoreConnectionRepository.currentConnection()
            ?: throw LiveTvRecordingException("Connect W Core in Settings to record TV.")
        val (origin, token) = session
        val response = httpRequestRaw("GET", "$origin/api/v2/session",
            headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json",
                "Cache-Control" to "no-store"), body = "", followRedirects = false,
            maxResponseBodyBytes = 64 * 1024)
        ensureRecordingRequestOwner()
        if (WCoreConnectionRepository.currentConnection() != session) {
            throw LiveTvRecordingException("Your account changed. Reopen Live TV and try again.")
        }
        if (response.status == 401 || response.status == 403) WCoreConnectionRepository.retry()
        if (response.status !in 200..299) {
            throw LiveTvRecordingException("Could not confirm the W Core profile (${response.status}).")
        }
        val id = runCatching { Json.parseToJsonElement(response.body).jsonObject["selectedProfileId"]
            ?.jsonPrimitive?.contentOrNull }.getOrNull()
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
            ?: throw LiveTvRecordingException("W Core did not confirm a recording profile.")
    }

    private suspend fun request(method: String, path: String, body: String = ""): JsonObject =
        requestAt(method, "/api/v1/live/native-recordings$path", body)

    private suspend fun requestAt(method: String, path: String, body: String = ""): JsonObject {
        ensureRecordingRequestOwner()
        val session = WCoreConnectionRepository.currentConnection()
            ?: throw LiveTvRecordingException("Connect W Core in Settings to record TV.")
        val (origin, token) = session
        val response = httpRequestRaw(method, "$origin$path",
            headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json",
                "Content-Type" to "application/json", "Cache-Control" to "no-store"),
            body = body, followRedirects = false, maxResponseBodyBytes = 128 * 1024)
        ensureRecordingRequestOwner()
        if (WCoreConnectionRepository.currentConnection() != session) {
            throw LiveTvRecordingException("Your account changed. Reopen Live TV and try again.")
        }
        if (response.status == 401 || response.status == 403) WCoreConnectionRepository.retry()
        if (response.status !in 200..299) {
            val code = runCatching { Json.parseToJsonElement(response.body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            throw LiveTvRecordingException(when (code) {
                "recording_storage_unavailable" -> "Recording storage is not configured in W Core."
                "recording_storage_low" -> "There is not enough free recording space."
                "recording_stream_ambiguous_or_missing" -> "This exact channel is not in the selected Core provider."
                "recording_guide_mismatch", "recording_channel_mismatch" -> "The guide or channel does not match this exact stream. Check the Core provider."
                "recording_guide_required" -> "This channel has no exact guide ID, so W Core cannot safely schedule it."
                "recording_provider_unavailable", "live_provider_not_found" -> "The selected Core provider is unavailable."
                "recording_stream_format_unsupported" -> "This stream format cannot be recorded yet."
                "recording_conflict" -> "Another recording overlaps this time. Choose a different slot."
                "recording_series_not_found" -> "This series link is no longer available. Refresh recordings."
                "invalid_record_now_programme", "recording_programme_mismatch" ->
                    "W Core could not confirm the current guide programme. Refresh the guide and try again."
                "invalid_record_now_mode" -> "Choose a valid recording duration."
                else -> if (method == "DELETE") "Could not remove recording (${response.status})."
                    else "Recording could not be scheduled (${response.status})."
            })
        }
        return runCatching { Json.parseToJsonElement(response.body).jsonObject }
            .getOrElse { throw LiveTvRecordingException("The recording server returned an invalid response.") }
    }

    suspend fun list(profileId: Int): List<LiveTvRecording> {
        val response = request("GET", "?profileId=${selectedCoreProfileId()}")
        if (response["available"]?.jsonPrimitive?.content == "false") {
            throw LiveTvRecordingException("Recording storage is not configured in W Core.")
        }
        return response.getValue("items").jsonArray.mapNotNull { (it as? JsonObject)?.let(::decodeLiveTvRecording) }
    }

    suspend fun listSeries(profileId: Int): List<CoreRecordingSeriesRule> {
        val response = requestAt("GET", "/api/v1/live/native-recording-series?profileId=${selectedCoreProfileId()}")
        return response["items"]?.jsonArray?.mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            val channel = item["channel"] as? JsonObject ?: return@mapNotNull null
            CoreRecordingSeriesRule(
                id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                programmeTitle = item["programmeTitle"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                channelName = channel["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                lastError = item["lastError"]?.jsonPrimitive?.contentOrNull,
            )
        }.orEmpty()
    }

    suspend fun deleteSeries(profileId: Int, id: String) {
        require(id.matches(Regex("[0-9a-fA-F-]{36}")))
        val response = requestAt("DELETE", "/api/v1/live/native-recording-series/$id?profileId=${selectedCoreProfileId()}")
        if (response["deleted"]?.jsonPrimitive?.content != "true") {
            throw LiveTvRecordingException("W Core did not confirm the series link was removed.")
        }
    }

    suspend fun coreProviders(): List<CoreRecordingProvider> {
        ensureRecordingRequestOwner()
        val session = WCoreConnectionRepository.currentConnection()
            ?: throw LiveTvRecordingException("Connect W Core in Settings to record TV.")
        val (origin, token) = session
        val response = httpRequestRaw("GET", "$origin/api/wcore/live/providers",
            headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json",
                "Cache-Control" to "no-store"), body = "", followRedirects = false,
            maxResponseBodyBytes = 128 * 1024)
        ensureRecordingRequestOwner()
        if (WCoreConnectionRepository.currentConnection() != session) {
            throw LiveTvRecordingException("Your account changed. Reopen Live TV and try again.")
        }
        if (response.status !in 200..299) {
            throw LiveTvRecordingException("Could not load W Core recording sources (${response.status}).")
        }
        val root = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: throw LiveTvRecordingException("W Core returned invalid recording sources.")
        return root["items"]?.jsonArray?.mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            if (value["sourceType"]?.jsonPrimitive?.contentOrNull != "xtream" ||
                value["enabled"]?.jsonPrimitive?.content != "true") return@mapNotNull null
            val id = value["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = value["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val programmeCount = value["programmeCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            CoreRecordingProvider(id, name, programmeCount)
        }.orEmpty()
    }

    suspend fun coreGuide(coreProviderId: String, streamId: String): CoreRecordingGuide {
        require(coreProviderId.matches(Regex("[0-9a-fA-F-]{36}")))
        require(streamId.matches(Regex("[0-9]{1,20}")))
        val response = request("GET", "/guide?providerId=$coreProviderId&streamId=$streamId")
        val guideId = response["guideId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val programmes = response["programmes"]?.jsonArray?.mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            val title = value["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val startMs = value["startMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val endMs = value["endMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            CoreRecordingProgramme(title, startMs, endMs)
        }.orEmpty()
        return CoreRecordingGuide(guideId, programmes,
            response["reason"]?.jsonPrimitive?.contentOrNull)
    }

    suspend fun alternatives(coreProviderId: String, streamId: String): List<CoreRecordingAlternative> {
        require(coreProviderId.matches(Regex("[0-9a-fA-F-]{36}")))
        require(streamId.matches(Regex("[0-9]{1,20}")))
        val response = request("GET", "/alternatives?providerId=$coreProviderId&streamId=$streamId")
        val expectedGuideId = response["guideId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: return emptyList()
        return response["items"]?.jsonArray?.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val channel = item["channel"] as? JsonObject ?: return@mapNotNull null
            val candidateId = item["streamId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.matches(Regex("[0-9]{1,20}")) } ?: return@mapNotNull null
            val guideId = channel["guideId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it == expectedGuideId } ?: return@mapNotNull null
            val name = channel["name"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            CoreRecordingAlternative(candidateId, name, guideId)
        }.orEmpty()
    }

    suspend fun artworkForProgramme(title: String): String? {
        val expected = normalizedRecordingTitle(title)
        if (expected.isBlank()) return null
        val query = listOf("q" to title, "type" to "series", "limit" to "5",
            "region" to "GB", "locale" to "en-GB").formUrlEncode()
        val response = requestAt("GET", "/api/wcore/metadata/search?$query")
        val exact = response["items"]?.jsonArray?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { item ->
                item["type"]?.jsonPrimitive?.contentOrNull == "series" &&
                    item["title"]?.jsonPrimitive?.contentOrNull
                        ?.let(::normalizedRecordingTitle) == expected
            } ?: return null
        return exact["poster"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: exact["backdrop"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
    }

    private suspend fun requireCoreProgramme(coreProviderId: String, streamId: String,
        channel: LiveTvChannel, programme: LiveTvProgramme) {
        val guide = coreGuide(coreProviderId, streamId)
        if (!guide.guideId.equals(channel.guideId, ignoreCase = true) ||
            guide.programmes.none { it.title == programme.title && it.startMs == programme.startEpochMs &&
                it.endMs == programme.stopEpochMs }) {
            throw LiveTvRecordingException("W Core has no exact guide match for this channel and programme.")
        }
    }

    suspend fun schedule(profileId: Int, channel: LiveTvChannel, programme: LiveTvProgramme,
        protectSport: Boolean, coreProviderId: String): LiveTvRecording {
        val streamId = exactXtreamRecordingStreamId(channel, programme)
        requireCoreProgramme(coreProviderId, streamId, channel, programme)
        val selectedProfileId = selectedCoreProfileId()
        val body = buildJsonObject {
            put("profileId", JsonPrimitive(selectedProfileId))
            put("providerId", JsonPrimitive(coreProviderId))
            put("streamId", JsonPrimitive(streamId))
            put("channel", buildJsonObject {
                put("id", JsonPrimitive(channel.id)); put("name", JsonPrimitive(channel.name))
                channel.guideId?.let { put("guideId", JsonPrimitive(it)) }
            })
            put("programme", buildJsonObject {
                put("title", JsonPrimitive(programme.title))
                put("startMs", JsonPrimitive(programme.startEpochMs)); put("endMs", JsonPrimitive(programme.stopEpochMs))
            })
            put("protectSport", JsonPrimitive(protectSport))
        }
        return decodeLiveTvRecording(request("POST", "", body.toString()).getValue("item").jsonObject)
            ?: throw LiveTvRecordingException("The recording server did not confirm this programme.")
    }

    suspend fun createSeries(profileId: Int, channel: LiveTvChannel, programme: LiveTvProgramme, coreProviderId: String): CoreRecordingSeriesRule {
        val streamId = exactXtreamRecordingStreamId(channel, programme)
        requireCoreProgramme(coreProviderId, streamId, channel, programme)
        val selectedProfileId = selectedCoreProfileId()
        val body = buildJsonObject {
            put("profileId", JsonPrimitive(selectedProfileId))
            put("providerId", JsonPrimitive(coreProviderId))
            put("streamId", JsonPrimitive(streamId))
            put("channel", buildJsonObject {
                put("id", JsonPrimitive(channel.id)); put("name", JsonPrimitive(channel.name))
                channel.guideId?.let { put("guideId", JsonPrimitive(it)) }
            })
            put("programme", buildJsonObject {
                put("title", JsonPrimitive(programme.title))
                put("startMs", JsonPrimitive(programme.startEpochMs)); put("endMs", JsonPrimitive(programme.stopEpochMs))
            })
        }
        val item = requestAt("POST", "/api/v1/live/native-recording-series", body.toString())["item"]?.jsonObject
            ?: throw LiveTvRecordingException("W Core did not confirm the series link.")
        val coreChannel = item["channel"] as? JsonObject
        return CoreRecordingSeriesRule(
            id = item["id"]?.jsonPrimitive?.contentOrNull
                ?: throw LiveTvRecordingException("W Core returned an invalid series link."),
            programmeTitle = item["programmeTitle"]?.jsonPrimitive?.contentOrNull
                ?: throw LiveTvRecordingException("W Core returned an invalid series link."),
            channelName = coreChannel?.get("name")?.jsonPrimitive?.contentOrNull
                ?: throw LiveTvRecordingException("W Core returned an invalid series link."),
            lastError = item["lastError"]?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun recordNow(profileId: Int, channel: LiveTvChannel, programme: LiveTvProgramme?,
        mode: RecordNowMode, coreProviderId: String, nowMs: Long): LiveTvRecording {
        val currentProgramme = recordNowProgramme(mode, programme, nowMs)
        val streamId = exactXtreamRecordingStreamId(channel, currentProgramme)
        if (currentProgramme != null) requireCoreProgramme(coreProviderId, streamId, channel, currentProgramme)
        val selectedProfileId = selectedCoreProfileId()
        val body = buildJsonObject {
            put("profileId", JsonPrimitive(selectedProfileId))
            put("providerId", JsonPrimitive(coreProviderId))
            put("streamId", JsonPrimitive(streamId))
            put("channel", buildJsonObject {
                put("id", JsonPrimitive(channel.id)); put("name", JsonPrimitive(channel.name))
                put("guideId", JsonPrimitive(requireNotNull(channel.guideId)))
            })
            put("mode", JsonPrimitive(mode.apiValue))
            if (currentProgramme != null) {
                val exactProgramme = currentProgramme
                put("programme", buildJsonObject {
                    put("title", JsonPrimitive(exactProgramme.title))
                    put("startMs", JsonPrimitive(exactProgramme.startEpochMs))
                    put("endMs", JsonPrimitive(exactProgramme.stopEpochMs))
                })
            }
        }
        return decodeLiveTvRecording(request("POST", "/record-now", body.toString()).getValue("item").jsonObject)
            ?: throw LiveTvRecordingException("W Core did not confirm the recording.")
    }

    suspend fun cancel(profileId: Int, id: String): LiveTvRecording = action(profileId, id, "cancel", "{}")
    suspend fun delete(profileId: Int, id: String): Boolean {
        require(id.matches(Regex("[0-9a-fA-F-]{36}")))
        val selectedProfileId = selectedCoreProfileId()
        val response = request("DELETE", "/$id?profileId=$selectedProfileId")
        if (response["deleted"]?.jsonPrimitive?.content != "true") {
            throw LiveTvRecordingException("W Core did not confirm removal. Refresh recordings before retrying.")
        }
        return response["pending"]?.jsonPrimitive?.content == "true"
    }
    suspend fun extend(profileId: Int, id: String, minutes: Int = 30): LiveTvRecording =
        action(profileId, id, "extend", "{\"minutes\":$minutes}")

    private suspend fun action(profileId: Int, id: String, verb: String, body: String): LiveTvRecording {
        require(id.matches(Regex("[0-9a-fA-F-]{36}")))
        val selectedProfileId = selectedCoreProfileId()
        return decodeLiveTvRecording(request("POST", "/$id/$verb?profileId=$selectedProfileId", body).getValue("item").jsonObject)
            ?: throw LiveTvRecordingException("The recording server returned an invalid programme.")
    }

    suspend fun playbackUrl(profileId: Int, id: String): String {
        require(id.matches(Regex("[0-9a-fA-F-]{36}")))
        ensureRecordingRequestOwner()
        val session = WCoreConnectionRepository.currentConnection()
            ?: throw LiveTvRecordingException("Connect W Core in Settings to play recordings.")
        val selectedProfileId = selectedCoreProfileId()
        val path = request("GET", "/$id/playback?profileId=$selectedProfileId")["path"]
            ?.jsonPrimitive?.contentOrNull ?: throw LiveTvRecordingException("Recording playback is unavailable.")
        if (!path.startsWith("/api/wcore/native-live-recording/") ||
            !path.removePrefix("/api/wcore/native-live-recording/").matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) ||
            WCoreConnectionRepository.currentConnection() != session) {
            throw LiveTvRecordingException("Recording playback is unavailable.")
        }
        return session.first + path
    }
}
