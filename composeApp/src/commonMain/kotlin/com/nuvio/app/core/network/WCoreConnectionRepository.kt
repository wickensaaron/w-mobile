package com.nuvio.app.core.network

import com.nuvio.app.core.auth.currentDeviceClientMetadata
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.addons.httpRequestRaw
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface WCoreConnectionStatus {
    data object NotConfigured : WCoreConnectionStatus
    data object SignInRequired : WCoreConnectionStatus
    data object Connecting : WCoreConnectionStatus
    data object Connected : WCoreConnectionStatus
    data object Unavailable : WCoreConnectionStatus
}

/** The W Core access token is short lived and deliberately never written to device storage. */
object WCoreConnectionRepository {
    private val origin = pinnedWCoreOrigin(WCoreConfig.BASE_URL)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private var generation = 0L
    private var exchangeJob: Job? = null
    private var userId: String? = null
    private var supabaseAccessToken: String? = null
    private var coreAccessToken: String? = null
    private var coreExpiresAt: Instant? = null

    private val _status = MutableStateFlow<WCoreConnectionStatus>(
        if (origin == null) WCoreConnectionStatus.NotConfigured else WCoreConnectionStatus.SignInRequired,
    )
    val status: StateFlow<WCoreConnectionStatus> = _status.asStateFlow()

    fun onSupabaseSession(userId: String, accessToken: String) {
        val configuredOrigin = origin ?: return
        if (userId.isBlank() || accessToken.isBlank()) {
            clear()
            return
        }
        synchronized(lock) {
            if (this.userId == userId && supabaseAccessToken == accessToken && exchangeJob?.isActive == true) {
                return
            }
            generation++
            exchangeJob?.cancel()
            this.userId = userId
            supabaseAccessToken = accessToken
            coreAccessToken = null
            coreExpiresAt = null
            _status.value = WCoreConnectionStatus.Connecting
            val currentGeneration = generation
            exchangeJob = scope.launch { exchangeLoop(configuredOrigin, accessToken, currentGeneration) }
        }
    }

    fun onProfileChanged() {
        val session = synchronized(lock) { userId to supabaseAccessToken }
        clear()
        if (session.first != null && session.second != null) {
            onSupabaseSession(session.first!!, session.second!!)
        }
    }

    fun retry() {
        onProfileChanged()
    }

    fun clear() {
        synchronized(lock) {
            generation++
            exchangeJob?.cancel()
            exchangeJob = null
            userId = null
            supabaseAccessToken = null
            coreAccessToken = null
            coreExpiresAt = null
            _status.value = if (origin == null) WCoreConnectionStatus.NotConfigured else WCoreConnectionStatus.SignInRequired
        }
    }

    fun currentAccessToken(): String? = synchronized(lock) {
        coreAccessToken?.takeIf { coreExpiresAt?.let { expiry -> Clock.System.now() < expiry } == true }
    }

    internal fun currentOrigin(): String? = origin

    private suspend fun exchangeLoop(origin: String, accessToken: String, expectedGeneration: Long) {
        while (currentCoroutineContext().isActive) {
            try {
                val session = exchangeWCoreSession(origin, accessToken)
                val accepted = synchronized(lock) {
                    if (generation != expectedGeneration) false else {
                        coreAccessToken = session.accessToken
                        coreExpiresAt = session.expiresAt
                        _status.value = WCoreConnectionStatus.Connected
                        true
                    }
                }
                if (!accepted) return
                val untilRefresh = session.expiresAt - Clock.System.now() - 1.minutes
                delay(untilRefresh.coerceIn(1.seconds, 14.minutes))
                synchronized(lock) {
                    if (generation == expectedGeneration) _status.value = WCoreConnectionStatus.Connecting
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                synchronized(lock) {
                    if (generation == expectedGeneration) {
                        coreAccessToken = null
                        coreExpiresAt = null
                        _status.value = WCoreConnectionStatus.Unavailable
                    }
                }
                delay(1.minutes)
                synchronized(lock) {
                    if (generation == expectedGeneration) _status.value = WCoreConnectionStatus.Connecting
                }
            }
        }
    }
}

internal data class WCoreSession(val accessToken: String, val expiresAt: Instant)

internal fun pinnedWCoreOrigin(value: String): String? {
    val candidate = value.trim().trimEnd('/')
    if (candidate.isBlank()) return null
    val match = Regex("^https://([A-Za-z0-9-]+\\.)+[A-Za-z0-9-]+(?::([0-9]{1,5}))?$").matchEntire(candidate)
        ?: return null
    val port = match.groupValues[2].toIntOrNull()
    if (port != null && port !in 1..65535) return null
    return candidate
}

internal fun parseWCoreSession(body: String, now: Instant = Clock.System.now()): WCoreSession? = runCatching {
    val objectValue = Json.parseToJsonElement(body).jsonObject
    val token = objectValue["accessToken"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: return@runCatching null
    val expiry = objectValue["accessExpiresAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse)
        ?: return@runCatching null
    if (expiry <= now) null else WCoreSession(token, expiry)
}.getOrNull()

private suspend fun exchangeWCoreSession(origin: String, supabaseAccessToken: String): WCoreSession {
    val metadata = currentDeviceClientMetadata()
    val requestBody = buildJsonObject {
        put("deviceId", SyncClientIdentity.currentClientId())
        put("deviceName", metadata.deviceName)
        put("platform", metadata.platform)
        put("appVersion", AppVersionConfig.VERSION_NAME)
    }.toString()
    val response = httpRequestRaw(
        method = "POST",
        url = "$origin/api/v1/auth/supabase",
        headers = mapOf(
            "Authorization" to "Bearer $supabaseAccessToken",
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        ),
        body = requestBody,
        followRedirects = false,
        maxResponseBodyBytes = 16 * 1024,
    )
    if (response.status !in 200..299) throw IllegalStateException("W Core authentication unavailable")
    return parseWCoreSession(response.body) ?: throw IllegalStateException("Invalid W Core authentication response")
}
