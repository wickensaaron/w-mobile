package com.nuvio.app.features.livetv

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveTvCompressedGuideTest {
    @Test
    fun providerReceivesGzipNegotiationAndOwnedGuideRowsAreDecoded() = runBlocking {
        val server = MockWebServer()
        try {
            val now = Clock.System.now()
            fun timestamp(ms: Long) = kotlin.time.Instant.fromEpochMilliseconds(ms).toString()
                .take(19).replace("-", "").replace(":", "").replace("T", "") + " +0000"
            val xml = "<tv><programme channel=\"bbc.uk\" start=\"${timestamp(now.toEpochMilliseconds() - 60_000)}\" " +
                "stop=\"${timestamp(now.toEpochMilliseconds() + 3_600_000)}\"><title>News</title></programme></tv>"
            val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(xml.toByteArray()) } }.toByteArray()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/xml").setHeader("Content-Encoding", "gzip")
                .setBody(Buffer().write(bytes)))
            server.start()
            val owner = LiveTvAccountScope("https://account.example.invalid", "owner", 1)
            val channel = LiveTvChannel("source-a:1", "UK: BBC One", "https://stream.example.invalid/1",
                playlistId = "source-a", guideId = "bbc.uk", accountScope = owner)
            val result = LiveTvAccountImport.providerGuide(server.url("/xmltv.php").toString(), "source-a", listOf(channel), 100) { true }
            assertEquals("News", result.programmes.getValue(channel.id).single().title)
            assertEquals("gzip", assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).getHeader("Accept-Encoding"))
        } finally { server.shutdown() }
    }
}
