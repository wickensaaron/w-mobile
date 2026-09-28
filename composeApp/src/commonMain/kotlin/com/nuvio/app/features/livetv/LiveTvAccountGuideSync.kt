package com.nuvio.app.features.livetv

import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.auth
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class LiveTvGuideRevisionConflict : IllegalStateException("Guide choices changed on another device")

/** Existing owner-only organiser RPC; credentials and response bodies are never logged. */
internal object LiveTvAccountGuideSync {
    private val http by lazy { createLiveTvImportHttpClient() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun pullPayload(owner: LiveTvAccountScope, isCurrent: () -> Boolean): String = request(
        owner, "live_tv_guide_pull", "{\"p_profile_id\":${owner.profile},\"p_include_catalog\":true}",
        LiveTvAccountGuideWrapperBytes, isCurrent,
    )

    suspend fun savePreferences(owner: LiveTvAccountScope, snapshot: LiveTvAccountGuideSnapshot,
        preferences: LiveTvAccountGuidePreferences, isCurrent: () -> Boolean): LiveTvAccountGuideSnapshot {
        val body = encodeSaveBody(owner.profile, snapshot.revision, preferences)
        val payload = request(owner, "live_tv_guide_save", body, 512 * 1024, isCurrent)
        val result = json.parseToJsonElement(payload) as? JsonObject ?: error("Invalid guide save response")
        val revision = (result["revision"] as? JsonPrimitive)?.longOrNull ?: error("Invalid guide save revision")
        val saved = result["preferences"]?.let { json.decodeFromJsonElement<LiveTvAccountGuidePreferences>(it) }
            ?: error("Missing guide save preferences")
        validateLiveTvAccountGuidePreferences(saved)
        check(revision == snapshot.revision + 1 && saved == preferences) { "Guide save response differs" }
        return snapshot.copy(revision = revision, preferences = saved)
    }

    internal fun encodeSaveBody(profile: Int, baseRevision: Long, preferences: LiveTvAccountGuidePreferences): String {
        require(profile in 1..6 && baseRevision >= 0 && baseRevision < Long.MAX_VALUE)
        validateLiveTvAccountGuidePreferences(preferences)
        val encoded = json.encodeToJsonElement(preferences)
        requireAccountGuideUtf8Budget(encoded.toString(), LiveTvAccountGuidePreferenceBytes)
        return buildJsonObject {
            put("p_profile_id", profile)
            put("p_base_revision", baseRevision)
            put("p_preferences", encoded)
        }.toString()
    }

    private suspend fun request(owner: LiveTvAccountScope, rpc: String, body: String, responseLimit: Int,
        isCurrent: () -> Boolean): String {
        val client = SupabaseProvider.client
        val configuration = ServerConfigurationRepository.active.value
        fun requireOwner() {
            check(isCurrent() && currentLiveTvAccountScope() == owner && client === SupabaseProvider.client &&
                configuration == ServerConfigurationRepository.active.value) { "Live TV account changed" }
        }
        require(owner.backend.startsWith("https://") && owner.profile in 1..6) { "Invalid Live TV account scope" }
        requireOwner()
        val token = client.auth.currentSessionOrNull()?.accessToken?.takeIf(String::isNotBlank)
            ?: error("Live TV sign-in unavailable")
        val payload = http.prepareRequest("${owner.backend}/rest/v1/rpc/$rpc") {
            method = HttpMethod.Post
            header("Authorization", "Bearer $token")
            header("apikey", configuration.publishableKey)
            header("Content-Type", "application/json")
            setBody(body)
        }.execute { response ->
            if (response.status.value == 409) throw LiveTvGuideRevisionConflict()
            check(response.status.value in 200..299) { "Guide choices request failed" }
            val channel = response.bodyAsChannel()
            val chunks = mutableListOf<ByteArray>()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                requireOwner()
                val size = channel.readAvailable(buffer)
                if (size < 0) break
                if (size == 0) continue
                check(size <= responseLimit - total) { "Guide choices exceed device limit" }
                chunks += buffer.copyOf(size)
                total += size
            }
            val bytes = ByteArray(total)
            var offset = 0
            chunks.forEach { chunk -> chunk.copyInto(bytes, offset); offset += chunk.size }
            bytes.decodeToString()
        }
        currentCoroutineContext().ensureActive()
        requireOwner()
        return payload
    }
}
