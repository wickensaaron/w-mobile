package com.nuvio.app.features.livetv

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal const val MobileLiveTvCatalogueLimit = 100_000
internal const val MobileLiveTvProgrammeLimit = 100_000

/** Scope and catalogue generation are process-only; no source credentials are persisted. */
data class LiveTvAccountScope(val backend: String, val account: String, val profile: Int)
data class LiveTvAccountSourceSummary(val id: String, val name: String, val type: String, val enabled: Boolean)

@Serializable
internal data class ImportedLiveTvSource(
    val id: String,
    val name: String,
    val type: String,
    val endpoint: String,
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val enabled: Boolean = true,
    val order: Int = 0,
) {
    override fun toString(): String = "ImportedLiveTvSource(type=$type, enabled=$enabled)"
    fun summary() = LiveTvAccountSourceSummary(id, name, type, enabled)
    fun xtreamSettings() = LiveTvXtreamSettings(endpoint, username, password, enabled)
    fun guideUrl(): String = epgUrl.ifBlank {
        if (type != "XTREAM") "" else "${endpoint.trimEnd('/').substringBefore("/player_api.php")}/xmltv.php" +
            "?username=${username.encodeURLParameter()}&password=${password.encodeURLParameter()}"
    }
}

@Serializable
internal data class ImportedLiveTvSnapshot(val revision: Long = 0, val providers: List<ImportedLiveTvSource> = emptyList())

internal fun ownsImportedLiveTvChannel(
    channel: LiveTvChannel,
    owner: LiveTvAccountScope?,
    currentOwner: LiveTvAccountScope?,
    generation: Long,
    sources: List<ImportedLiveTvSource>,
): Boolean = owner != null && owner == currentOwner && channel.accountScope == owner &&
    channel.accountSourceGeneration == generation && sources.any {
        it.enabled && it.id == channel.playlistId && channel.id.startsWith("${it.id}:")
    }

internal expect fun createLiveTvImportHttpClient(): HttpClient

internal fun currentLiveTvAccountScope(): LiveTvAccountScope? {
    val auth = AuthRepository.state.value as? AuthState.Authenticated ?: return null
    if (auth.isAnonymous || auth.userId.isBlank()) return null
    val profile = ProfileRepository.state.value.activeProfile ?: return null
    if (profile.userId != auth.userId || profile.profileIndex != ProfileRepository.activeProfileId) return null
    val client = SupabaseProvider.client
    val configuration = ServerConfigurationRepository.active.value
    if (client.supabaseHttpUrl.trimEnd('/') != configuration.backendUrl.trim().trimEnd('/') ||
        client.supabaseKey != configuration.publishableKey) return null
    val session = client.auth.currentSessionOrNull() ?: return null
    if (session.user?.id != auth.userId || session.accessToken.isBlank()) return null
    return LiveTvAccountScope(configuration.backendUrl.trim().trimEnd('/'), auth.userId, profile.profileIndex)
}

internal fun decodeImportedLiveTvSources(payload: String): ImportedLiveTvSnapshot {
    val result = Json { ignoreUnknownKeys = true }.decodeFromString<ImportedLiveTvSnapshot>(payload)
    require(result.revision >= 0 && result.providers.size <= 100) { "Invalid Live TV source snapshot" }
    val ids = mutableSetOf<String>()
    result.providers.forEach { source ->
        require(source.id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) && ids.add(source.id)) {
            "Invalid Live TV source identity"
        }
        require(source.type == "M3U" || source.type == "XTREAM") { "Unsupported Live TV source type" }
        require(source.name.isNotBlank() && source.name.length <= 160 && source.username.length <= 512 &&
            source.password.length <= 4096 && source.order in 0..99) {
            "Invalid Live TV source fields"
        }
        require(source.endpoint.isImportedLiveTvWebUrl()) { "Invalid Live TV source endpoint" }
        require(source.epgUrl.isBlank() || source.epgUrl.isImportedLiveTvWebUrl()) { "Invalid Live TV guide endpoint" }
        require(source.type != "XTREAM" || source.username.isNotBlank() && source.password.isNotBlank()) {
            "Incomplete Live TV source"
        }
    }
    return result.copy(providers = result.providers.sortedWith(compareBy(ImportedLiveTvSource::order, ImportedLiveTvSource::id)))
}

private fun String.isImportedLiveTvWebUrl(): Boolean = length <= 4096 && runCatching {
    val parsed = Url(this)
    parsed.protocol.name in setOf("https", "http") && parsed.host.isNotBlank()
}.getOrDefault(false)

/** Dedicated transport has no redirect, retry, fallback, logging or disk cache. */
internal object LiveTvAccountImport {
    private val http by lazy { createLiveTvImportHttpClient() }

    suspend fun pull(owner: LiveTvAccountScope): ImportedLiveTvSnapshot {
        val client = SupabaseProvider.client
        val configuration = ServerConfigurationRepository.active.value
        fun requireOwner() {
            check(currentLiveTvAccountScope() == owner && client === SupabaseProvider.client &&
                configuration == ServerConfigurationRepository.active.value) { "Live TV account changed" }
        }
        requireOwner()
        require(owner.backend.startsWith("https://")) { "Live TV account connection must use HTTPS" }
        val token = client.auth.currentSessionOrNull()?.accessToken ?: error("Live TV sign-in unavailable")
        val payload = requestText(
            "${owner.backend}/functions/v1/live-tv-sync", 4 * 1024 * 1024, HttpMethod.Post,
            mapOf("Authorization" to "Bearer $token", "apikey" to configuration.publishableKey,
                "Content-Type" to "application/json"), "{\"action\":\"pull\"}",
        )
        currentCoroutineContext().ensureActive()
        requireOwner()
        return decodeImportedLiveTvSources(payload)
    }

    suspend fun providerText(url: String, headers: Map<String, String>, maxBytes: Int = 32 * 1024 * 1024,
        isCurrent: () -> Boolean = { true }): String {
        require(url.length <= 65_536 && runCatching { Url(url).protocol.name in setOf("http", "https") }.getOrDefault(false)) {
            "Invalid Live TV endpoint"
        }
        check(isCurrent()) { "Live TV source changed" }
        val text = requestText(url, maxBytes, HttpMethod.Get, headers)
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "Live TV source changed" }
        return text
    }

    /**
     * Identity encoding is requested to avoid invisible engine decoding. If the server ignores it,
     * the delivered-byte budget counts whatever the engine actually exposes (compressed OR already
     * decoded); a second independent budget counts XML bytes after optional gzip magic detection.
     * No response string, full byte array, disk file, redirect or retry is used for this guide lane.
     */
    suspend fun providerGuide(
        url: String,
        sourceId: String,
        channels: List<LiveTvChannel>,
        programmeBudget: Int,
        isCurrent: () -> Boolean,
    ): ImportedXmlTvResult = withContext(Dispatchers.Default) {
        require(url.length <= 65_536 && runCatching {
            val parsed = Url(url)
            parsed.protocol.name in setOf("http", "https") && parsed.host.isNotBlank()
        }.getOrDefault(false)) { "Invalid Live TV guide endpoint" }
        check(isCurrent()) { "Live TV guide source changed" }
        val limits = ImportedXmlTvLimits(retainedProgrammes = programmeBudget)
        try {
            // The coroutine deadline includes connection, transfer, decompression and parsing.
            withTimeout(180_000) {
                val collector = ImportedXmlTvCollector(sourceId, channels, Clock.System.now().toEpochMilliseconds(),
                    currentCoroutineContext(), isCurrent, limits)
                http.prepareRequest(url) {
                    method = HttpMethod.Get
                    header("Accept", "application/xml, text/xml, application/gzip, */*")
                    header("Accept-Encoding", "identity")
                    timeout { requestTimeoutMillis = 180_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 30_000 }
                }.execute { response ->
                    collector.checkCurrent()
                    check(response.status.value in 200..299) { "Live TV guide request failed" }
                    val channel = response.bodyAsChannel()
                    val deliveredBudget = ImportedXmlTvByteBudget(limits.deliveredBytes)
                    val result = parseImportedXmlTvStream(read = { buffer ->
                        collector.checkCurrent()
                        val size = channel.readAvailable(buffer)
                        collector.checkCurrent()
                        if (size > 0) {
                            deliveredBudget.accept(size)
                        } else if (size == 0) yield()
                        size
                    }, collector, limits)
                    collector.checkCurrent()
                    result
                }
            }
        } catch (error: TimeoutCancellationException) {
            // A provider deadline is a load failure; caller/sign-out cancellation still propagates.
            currentCoroutineContext().ensureActive()
            check(isCurrent()) { "Live TV guide source changed" }
            throw IllegalStateException("Live TV guide timed out")
        }
    }

    private suspend fun requestText(url: String, maxBytes: Int, method: HttpMethod,
        headers: Map<String, String>, body: String = ""): String = http.prepareRequest(url) {
        this.method = method
        headers.forEach { (key, value) -> header(key, value) }
        if (method == HttpMethod.Post) setBody(body)
    }.execute { response ->
        check(response.status.value in 200..299) { "Live TV request failed" }
        val channel = response.bodyAsChannel()
        val chunks = mutableListOf<ByteArray>()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val size = channel.readAvailable(buffer)
            if (size < 0) break
            if (size == 0) continue
            check(size <= maxBytes - total) { "Live TV response exceeds device limit" }
            chunks += buffer.copyOf(size)
            total += size
        }
        val bytes = ByteArray(total)
        var offset = 0
        chunks.forEach { chunk -> chunk.copyInto(bytes, offset); offset += chunk.size }
        bytes.decodeToString()
    }
}
