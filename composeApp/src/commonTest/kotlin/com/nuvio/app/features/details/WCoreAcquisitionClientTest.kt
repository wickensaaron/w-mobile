package com.nuvio.app.features.details

import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WCoreAcquisitionClientTest {
    private val mediaId = "wm_abcdefgh1234"
    private val identity = WCoreAcquisitionIdentity("movie", "A Film", 2025, "123", "tt1234567")

    @Test
    fun movieAndSeriesDetailIdsBecomeEligibleCanonicalRequests() {
        val movie = MetaDetails(id = "tmdb:123", type = "movie", name = "A Film", releaseInfo = "2025")
        val series = MetaDetails(id = "tmdb:series:456", type = "series", name = "A Show")
        assertEquals("123", movie.wCoreAcquisitionIdentity()?.tmdbId)
        assertEquals(2025, movie.wCoreAcquisitionIdentity()?.year)
        assertEquals("456", series.wCoreAcquisitionIdentity()?.tmdbId)
        assertFalse(MetaDetails(id = "tt1234567", type = "movie", name = "A Film")
            .wCoreAcquisitionIdentity()!!.canRequest)
        assertEquals(null, MetaDetails(id = "live:1", type = "channel", name = "Live").wCoreAcquisitionIdentity())
    }

    @Test
    fun canonicalResolutionAndAcquisitionListShowDownloadingProgress() = runBlocking {
        val requests = mutableListOf<String>()
        val client = fakeClient { method, url, _, _ ->
            requests += "$method $url"
            when {
                url.contains("/media/resolve?") -> response(200, """{"media":{"id":"$mediaId"}}""")
                url.contains("/mappings") -> response(200, """{"mappings":[]}""")
                url.contains("/acquisitions?") -> response(200, """{"items":[${job("active", "active", "not_available", 0.43)}],"nextCursor":null}""")
                else -> error("Unexpected request")
            }
        }

        val status = client.status(identity)

        assertEquals(WCoreAcquisitionState.DOWNLOADING, status.state)
        assertEquals(43, status.progressPercent)
        assertTrue(requests[0].contains("provider=tmdb&externalId=123&mediaType=movie"))
        assertTrue(requests[2].contains("/api/v1/acquisitions?mediaId=$mediaId&limit=1"))
    }

    @Test
    fun unresolvedTitleCanBeRequestedWithoutAnExistingAcquisition() = runBlocking {
        val requests = mutableListOf<String>()
        val client = fakeClient { _, url, headers, _ ->
            requests += url
            assertEquals("Bearer secret", headers["Authorization"])
            response(404, """{"code":"canonical_media_not_found"}""")
        }

        assertEquals(WCoreAcquisitionState.NOT_REQUESTED, client.status(identity).state)
        assertEquals(2, requests.size)
    }

    @Test
    fun createUsesCanonicalIdAndReturnsDeduplicatedRequestStatus() = runBlocking {
        val requests = mutableListOf<Pair<String, String>>()
        val client = fakeClient { method, url, _, body ->
            requests += url to body
            when {
                url.endsWith("/media/resolve") -> response(200, """{"media":{"id":"$mediaId"}}""")
                url.endsWith("/acquisitions") -> response(
                    200, """{"duplicate":true,"item":${job("active", "not_started", "not_available")}}""",
                )
                else -> error("Unexpected request")
            }
        }

        val status = client.request(identity)

        assertEquals(WCoreAcquisitionState.QUEUED, status.state)
        assertTrue(requests[0].second.contains("\"tmdb\":\"123\""))
        assertTrue(requests[1].second.contains("\"mediaId\":\"$mediaId\""))
        assertTrue(requests[1].second.contains("\"jobType\":\"ADD_TO_LIBRARY\""))
        assertTrue(requests[1].second.contains("\"action\":\"ADD_TO_LIBRARY\""))
    }

    @Test
    fun availableMappingAndFailedRetryAreReported() = runBlocking {
        var failed = false
        val client = fakeClient { _, url, _, _ ->
            when {
                url.contains("/media/resolve?") -> response(200, """{"media":{"id":"$mediaId"}}""")
                url.contains("/mappings") -> response(200, """{"mappings":[{"provider":"jellyfin"}]}""")
                url.contains("/acquisitions?") -> response(
                    200, if (failed) """{"items":[${job("failed", "failed", "failed")}]}"""
                    else """{"items":[]}""",
                )
                url.endsWith("/retry") -> response(200, """{"item":${job("active", "not_started", "not_available")}}""")
                else -> error("Unexpected request")
            }
        }
        assertEquals(WCoreAcquisitionState.AVAILABLE, client.status(identity).state)
        failed = true
        val status = client.status(identity)
        assertEquals(WCoreAcquisitionState.FAILED, status.state)
        assertTrue(status.canRetry)
        assertEquals(WCoreAcquisitionState.QUEUED, client.retry(assertNotNull(status.acquisitionId)).state)
    }

    @Test
    fun changedConnectionCannotAcceptOldServerResponse() = runBlocking {
        var connection: Pair<String, String>? = "https://core.example" to "secret"
        val client = WCoreAcquisitionClient(
            connection = { connection },
            transport = { _, _, _, _ ->
                connection = "https://other.example" to "other-secret"
                response(200, """{"media":{"id":"$mediaId"}}""")
            },
        )
        val result = runCatching { client.status(identity) }
        assertTrue(result.exceptionOrNull() is WCoreAcquisitionUnavailable)
    }

    private fun fakeClient(
        respond: suspend (String, String, Map<String, String>, String) -> RawHttpResponse,
    ) = WCoreAcquisitionClient(connection = { "https://core.example" to "secret" }, transport = respond)

    private fun response(status: Int, body: String) =
        RawHttpResponse(status, "", "https://core.example", body, emptyMap())

    private fun job(request: String, transfer: String, library: String, progress: Double = 0.0) =
        """{"id":"11111111-1111-1111-1111-111111111111","mediaId":"$mediaId",
           "request":{"state":"$request"},"acquisition":{"state":"$request"},
           "transfer":{"state":"$transfer","progress":$progress},
           "library":{"state":"$library"},"allowedActions":{"retry":true}}"""
}
