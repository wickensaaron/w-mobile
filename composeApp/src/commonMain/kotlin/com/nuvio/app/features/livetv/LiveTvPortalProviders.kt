package com.nuvio.app.features.livetv

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private const val STALKER_PLAYLIST_ID = "provider:stalker"
private const val XTREAM_PLAYLIST_ID = "provider:xtream"

private val portalJson = Json { ignoreUnknownKeys = true; isLenient = true }
private val playlistRequestHeaders = mapOf("Accept" to "application/json, text/plain, */*", "User-Agent" to "W/1.0")
private val streamRequestHeaders = mapOf("User-Agent" to "Mozilla/5.0", "Accept" to "*/*")
private val importedStreamIdPattern = Regex("[A-Za-z0-9_-]{1,80}")

internal suspend fun fetchXtreamChannels(
    settings: LiveTvXtreamSettings,
    accountSource: ImportedLiveTvSource? = null,
    isCurrent: () -> Boolean = { true },
): List<LiveTvChannel> {
    val normalized = settings.normalized()
    val categories = xtreamRequest(normalized, "get_live_categories", accountSource != null, isCurrent).arrayOrEmpty().take(10_000).mapIndexedNotNull { index, element ->
        if (accountSource != null && index % 256 == 0) {
            currentCoroutineContext().ensureActive()
            yield()
            check(isCurrent()) { "Live TV source changed" }
        }
        val item = element as? JsonObject ?: return@mapIndexedNotNull null
        val id = item.string("category_id") ?: item.string("id") ?: return@mapIndexedNotNull null
        id to (item.string("category_name") ?: item.string("name") ?: id)
    }.toMap()
    val streams = xtreamRequest(normalized, "get_live_streams", accountSource != null, isCurrent).arrayOrEmpty()
    if (accountSource != null) require(streams.size <= MobileLiveTvCatalogueLimit) { "Account source exceeds device limit" }
    val channels = streams.mapIndexedNotNull { index, element ->
        if (accountSource != null && index % 256 == 0) {
            currentCoroutineContext().ensureActive()
            yield()
            check(isCurrent()) { "Live TV source changed" }
        }
        val item = element as? JsonObject ?: return@mapIndexedNotNull null
        val name = item.string("name") ?: return@mapIndexedNotNull null
        if (accountSource != null && name.isBlank()) return@mapIndexedNotNull null
        val streamId = item.string("stream_id") ?: item.string("id") ?: return@mapIndexedNotNull null
        if (accountSource != null && !streamId.matches(importedStreamIdPattern)) return@mapIndexedNotNull null
        val extension = item.string("container_extension")?.trim()?.trimStart('.')?.ifBlank { null } ?: "ts"
        val directSource = item.string("direct_source")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        LiveTvChannel(
            id = accountSource?.let { "${it.id}:$streamId" } ?: "xtream:$streamId",
            name = name,
            streamUrl = directSource ?: normalized.liveStreamUrl(streamId, extension),
            logoUrl = item.string("stream_icon") ?: item.string("logo"),
            group = item.string("category_id")?.let(categories::get),
            playlistId = accountSource?.id ?: XTREAM_PLAYLIST_ID,
            playlistName = accountSource?.name ?: "Xtream",
            headers = streamRequestHeaders,
            streamType = extension,
            guideId = item.string("epg_channel_id") ?: item.string("tvg_id"),
            archive = parseLiveTvXtreamArchiveCapability(item.string("tv_archive"), item.string("tv_archive_duration")),
        )
    }
    return if (accountSource == null) channels.distinctBy { it.streamUrl } else channels
}

private suspend fun xtreamRequest(settings: LiveTvXtreamSettings, action: String, imported: Boolean = false,
    isCurrent: () -> Boolean = { true }): JsonElement {
    val url = "${settings.serverUrl}/player_api.php?username=${settings.username.encodeURLParameter()}&password=${settings.password.encodeURLParameter()}&action=${action.encodeURLParameter()}"
    val payload = if (imported) LiveTvAccountImport.providerText(url, playlistRequestHeaders, isCurrent = isCurrent)
        else httpGetTextWithHeaders(url, playlistRequestHeaders)
    if (imported) {
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "Live TV source changed" }
    }
    val parsed = portalJson.parseToJsonElement(payload)
    if (imported) {
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "Live TV source changed" }
    }
    return parsed
}

private data class StalkerSession(val settings: LiveTvStalkerSettings, val token: String)
private var cachedStalkerSession: StalkerSession? = null

internal fun clearStalkerSession() {
    cachedStalkerSession = null
}

internal suspend fun fetchStalkerChannels(settings: LiveTvStalkerSettings): List<LiveTvChannel> {
    val session = stalkerSession(settings.normalized())
    val genres = stalkerRequest(session.settings, session.token, "itv", "get_genres")
        .js().array("data").mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("id") ?: item.string("alias") ?: return@mapNotNull null
            id to (item.string("title") ?: item.string("name") ?: id)
        }.toMap()
    val channels = mutableListOf<LiveTvChannel>()
    for (page in 1..20) {
        val data = stalkerRequest(session.settings, session.token, "itv", "get_ordered_list", mapOf("p" to page.toString()))
            .js().array("data")
        if (data.isEmpty()) break
        data.forEachIndexed { index, element ->
            val item = element as? JsonObject ?: return@forEachIndexed
            val command = item.string("cmd") ?: item.string("mc_cmd") ?: item.string("url") ?: return@forEachIndexed
            val url = command.playableUrl()
            if (url.isBlank()) return@forEachIndexed
            LiveTvChannel(
                id = "stalker:${item.string("id") ?: "$page:$index"}",
                name = item.string("name") ?: item.string("title") ?: return@forEachIndexed,
                streamUrl = url,
                logoUrl = item.string("logo") ?: item.string("logo_url"),
                group = (item.string("tv_genre_id") ?: item.string("genre_id"))?.let(genres::get),
                playlistId = STALKER_PLAYLIST_ID,
                playlistName = "Stalker Portal",
                headers = stalkerHeaders(session.settings, session.token),
                stalkerCommand = command,
            ).also(channels::add)
        }
    }
    return channels.distinctBy { it.id }
}

internal suspend fun preparePortalChannelForPlayback(channel: LiveTvChannel, settings: LiveTvStalkerSettings): LiveTvChannel {
    val command = channel.stalkerCommand?.takeIf(String::isNotBlank) ?: return channel
    val session = stalkerSession(settings.normalized())
    val result = stalkerRequest(session.settings, session.token, "itv", "create_link", mapOf("cmd" to command)).js()
    val url = (result.string("cmd") ?: result.string("url") ?: result.string("stream_url"))?.playableUrl()
    return channel.copy(
        streamUrl = url?.takeIf(String::isNotBlank) ?: channel.streamUrl,
        headers = channel.headers + stalkerHeaders(session.settings, session.token),
    )
}

private suspend fun stalkerSession(settings: LiveTvStalkerSettings): StalkerSession {
    cachedStalkerSession?.takeIf { it.settings == settings && it.token.isNotBlank() }?.let { return it }
    val token = stalkerRequest(settings, null, "stb", "handshake").js().string("token").orEmpty()
    require(token.isNotBlank()) { "Stalker Portal did not return an authorization token." }
    return StalkerSession(settings, token).also { cachedStalkerSession = it }
}

private suspend fun stalkerRequest(
    settings: LiveTvStalkerSettings,
    token: String?,
    type: String,
    action: String,
    extra: Map<String, String> = emptyMap(),
): JsonObject {
    val parameters = buildMap {
        put("type", type); put("action", action); put("JsHttpRequest", "1-xml")
        if (!token.isNullOrBlank()) put("token", token)
        if (settings.username.isNotBlank()) put("login", settings.username)
        if (settings.password.isNotBlank()) put("password", settings.password)
        putAll(extra)
    }
    val endpoint = settings.portalEndpoint()
    val query = parameters.entries.joinToString("&", prefix = if ('?' in endpoint) "&" else "?") { (key, value) ->
        "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
    }
    return portalJson.parseToJsonElement(httpGetTextWithHeaders(endpoint + query, stalkerHeaders(settings, token))).jsonObject
}

private fun stalkerHeaders(settings: LiveTvStalkerSettings, token: String?): Map<String, String> = buildMap {
    put("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; MAG254; en) AppleWebKit/533.3 MAG200 stbapp ver: 4 rev: 2721 Mobile Safari/533.3")
    put("X-User-Agent", "Model: MAG254; Link: Ethernet")
    put("Referer", settings.portalBaseUrl())
    put("Cookie", "mac=${settings.macAddress}; stb_lang=en; timezone=Europe%2FIstanbul")
    if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
}

private fun LiveTvXtreamSettings.normalized() = copy(
    serverUrl = serverUrl.trim().trimEnd('/').substringBefore("/player_api.php").trimEnd('/'),
    username = username.trim(), password = password.trim(),
)
private fun LiveTvXtreamSettings.liveStreamUrl(id: String, extension: String) =
    "$serverUrl/live/${username.encodeURLParameter()}/${password.encodeURLParameter()}/${id.encodeURLParameter()}.${extension.encodeURLParameter()}"
private fun LiveTvStalkerSettings.normalized() = copy(
    portalUrl = portalUrl.trim().trimEnd('/'), macAddress = macAddress.trim().uppercase(),
    username = username.trim(), password = password.trim(),
)
private fun LiveTvStalkerSettings.portalEndpoint(): String {
    val normalized = portalUrl.trim().trimEnd('/')
    return when {
        normalized.contains("portal.php", ignoreCase = true) -> normalized
        normalized.contains("server/load.php", ignoreCase = true) -> normalized
        normalized.endsWith("/c", ignoreCase = true) -> normalized.dropLast(2) + "/portal.php"
        else -> "$normalized/portal.php"
    }
}
private fun LiveTvStalkerSettings.portalBaseUrl(): String {
    val base = portalUrl.trim()
        .substringBefore("/portal.php")
        .substringBefore("/server/load.php")
        .trimEnd('/')
        .removeSuffix("/c")
    return "$base/c/"
}
private fun String.playableUrl() = trim().removePrefix("ffmpeg ").removePrefix("auto ").substringBefore(' ').trim()
private fun JsonObject.js() = (this["js"] as? JsonObject) ?: this
private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
private fun JsonObject.array(key: String) = (this[key] as? JsonArray)?.toList().orEmpty()
private fun JsonElement.arrayOrEmpty() = (this as? JsonArray)?.toList() ?: (this as? JsonObject)?.array("data").orEmpty()
