package com.nuvio.app.features.streams

import com.nuvio.app.core.network.WCoreLibraryScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class WCoreSourceRegistryTest {
    private val owner = WCoreLibraryScope("account", 1, "https://core.example", "secret", 7)
    private val request = PreparedWCorePlaybackRequest(owner.origin, owner.token, """{"mediaId":"wm_movie123"}""", owner)
    private val response = """{"mediaId":"wm_movie123"}"""
    private val stream = StreamItem(url = "https://core.example/api/wcore/stream/signed-ticket", sourceName = "jellyfin:item/one",
        addonName = "Core", addonId = "wcore:jellyfin", externalSubtitles = listOf(StreamSubtitle("signed-subtitle", "en")),
        behaviorHints = StreamBehaviorHints(proxyHeaders = StreamProxyHeaders(request = mapOf("Authorization" to "Bearer secret"))))

    @Test fun pickerAndRegistryRetainNoPlaybackCredentials() {
        val registry = WCoreSourceRegistry()
        val displayed = registry.remember(listOf(stream), response, request).single()
        assertTrue(displayed.url!!.startsWith("wcore-source://"))
        assertTrue(displayed.externalSubtitles.isEmpty())
        assertNull(displayed.behaviorHints.proxyHeaders)
        val selection = registry.find(displayed)!!
        assertEquals(stream.sourceName, selection.sourceId)
        assertEquals(selection.owner, owner.copy(token = "renewed").owner)
        assertNotEquals(selection.owner, owner.copy(revision = 8).owner)
        assertFalse(selection.toString().contains("signed-ticket"))
        assertFalse(selection.toString().contains("secret"))
        assertNull(registry.find(displayed.copy(sourceName = "different-source")))
        assertNull(registry.find(displayed.copy(addonId = "wcore:other")))
    }

    @Test fun boundedRegistryRejectsEvictedAndClearedSelections() {
        val registry = WCoreSourceRegistry()
        val first = registry.remember(listOf(stream), response, request).single()
        repeat(40) { registry.remember(listOf(stream), response, request) }
        assertNull(registry.find(first))
        val last = registry.remember(listOf(stream), response, request).single()
        registry.clear()
        assertNull(registry.find(last))
    }

    @Test fun refreshNeverSubstitutesAnotherSourceOrProvider() {
        val other = stream.copy(sourceName = "jellyfin:other")
        assertNull(exactWCoreRefreshedSource(listOf(other), stream.sourceName!!, stream.addonId))
        assertNull(exactWCoreRefreshedSource(listOf(stream.copy(addonId = "wcore:other")), stream.sourceName!!, stream.addonId))
        assertEquals(stream, exactWCoreRefreshedSource(listOf(other, stream), stream.sourceName!!, stream.addonId))
        assertEquals("jellyfin%3Aitem%2Fone", encodeWCoreSourceId(stream.sourceName!!))
        val body = wCoreSourceRefreshBody(Json.parseToJsonElement(response) as JsonObject, request.body)!!
        assertTrue(body.contains("wm_movie123"))
    }

    @Test fun scopedRequestsRejectLifecycleChangeAndCancelledCompletion() = runBlocking {
        var current = true
        assertFailsWith<IllegalStateException> {
            requestInWCorePlaybackScope({ current }) { current = false; "late result" }
        }
        val job = launch {
            assertFailsWith<CancellationException> {
                requestInWCorePlaybackScope({ true }) { currentCoroutineContext().cancel(); "late result" }
            }
        }
        job.join()
    }
}
