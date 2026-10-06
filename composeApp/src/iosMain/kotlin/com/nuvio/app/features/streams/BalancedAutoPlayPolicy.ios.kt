package com.nuvio.app.features.streams

import com.nuvio.app.features.player.NuvioPlayerBridgeFactory
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal actual val balancedAutoPlayEnabled: Boolean = true

internal actual suspend fun verifyBalancedAutoPlay(stream: StreamItem): Boolean {
    if (!BalancedAutoPlayPolicy.isEligible(stream)) return false
    val url = stream.playableDirectUrl ?: return false
    return withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val bridge = NuvioPlayerBridgeFactory.create() ?: return@withContext false
        try {
            val headers = Json.encodeToString(sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request))
            val trackId = runCatching {
                bridge.probeAutoPlay(url, headers, BalancedAutoPlayPolicy.MAX_BYTES, 8_000)
            }.getOrDefault(-1)
            currentCoroutineContext().ensureActive()
            trackId >= 0
        } finally {
            bridge.destroy()
        }
    }
}
