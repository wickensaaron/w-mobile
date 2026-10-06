package com.nuvio.app.features.downloads

import android.content.pm.ProviderInfo
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import com.nuvio.app.R
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.addons.AddAddonResult
import com.nuvio.app.features.player.ExternalPlayerPlaybackRequest
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleFileCache
import com.nuvio.app.features.player.prepareExternalPlayerLaunch
import com.nuvio.app.features.player.SubtitleAddonRequest
import com.nuvio.app.features.player.SubtitleRepository
import com.nuvio.app.features.streams.StreamSubtitle
import java.io.File
import java.net.URI
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.xmlpull.v1.XmlPullParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadSubtitlesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun backgroundDownloadSavesAddonAndStreamSubtitlesBeforeVideo(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val playerSubtitles = SubtitleRepository.addonSubtitles.value
        val server = MockWebServer()
        server.start()
        val addonUrl = server.url("/english").toString()
        val frenchUrl = server.url("/french").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return when (request.path) {
                    "/subtitles/series/tt123%3A1%3A2.json" -> MockResponse().setBody(
                        """{"subtitles":[{"id":"english","url":"$addonUrl","lang":"eng"},{"id":"french","url":"$frenchUrl","lang":"fre"}]}""",
                    )
                    "/english" -> MockResponse().setBody(srt)
                    "/french" -> if (request.getHeader("Authorization") == "Bearer subtitle") {
                        MockResponse().setBody("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nBonjour\n")
                    } else MockResponse().setResponseCode(401)
                    "/video" -> MockResponse().setBody("complete video")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val scheduler = AndroidDownloadScheduler(context)
        val item = downloadItem(server.url("/video").toString()).copy(
            fileName = "offline-episode.mkv",
            contentType = "series",
            videoId = "tt123:1:2",
            subtitleRequests = listOf(SubtitleAddonRequest(
                server.url("/subtitles/series/tt123%3A1%3A2.json").toString(), "opensubtitles", "OpenSubtitles",
            )),
            sourceSubtitles = listOf(
                StreamSubtitle(server.url("/french").toString(), "fr", "French", mapOf("Authorization" to "Bearer subtitle")),
            ),
        )
        try {
            val transfer = scheduler.store.begin(item)
            assertFalse(scheduler.execute(transfer) { })
            val completed = assertNotNull(scheduler.store.get(item.fileName)).item
            assertEquals(DownloadStatus.Completed, completed.status, "In-memory transfer failed: ${completed.errorMessage}")
            val reloaded = AndroidDownloadStore(File(context.filesDir, "download-transfers")).get(item.fileName)
            assertNotNull(reloaded, "Persisted transfer could not be decoded; records: " +
                File(context.filesDir, "download-transfers").listFiles().orEmpty().map { it.name })
            assertEquals(DownloadStatus.Completed, reloaded.item.status, "Persisted transfer was not completed")
        } finally {
            server.shutdown()
        }

        val restored = AndroidDownloadScheduler(context).restore(item)
        assertEquals(DownloadStatus.Completed, restored.status, "Download failed: ${restored.errorMessage}; requested paths: $paths")
        val uri = assertNotNull(restored.localFileUri)
        val tracks = DownloadSubtitles.localSubtitles(uri)
        assertEquals(DownloadStatus.Completed, restored.status)
        assertEquals(setOf("en", "fr"), tracks.map { it.language }.toSet())
        assertEquals("/video", paths.last())
        assertEquals(1, paths.count { it == "/english" })
        assertEquals(1, paths.count { it == "/french" })
        assertTrue(tracks.single { it.language == "en" }.name.orEmpty().contains("OpenSubtitles"))
        assertEquals(playerSubtitles, SubtitleRepository.addonSubtitles.value)
        tracks.forEach {
            assertTrue(it.url.startsWith("file:"))
            assertTrue(it.headers.isNullOrEmpty())
            assertTrue(PlayerSubtitleCueParser.parse(File(URI(it.url)).readText(), it.url).isNotEmpty())
        }
        assertTrue(tracks.single { it.language == "fr" }.url.endsWith(".vtt"))
        withTimeout(1_000) { DownloadSubtitles.prepare(restored, uri) }
        assertEquals(tracks, DownloadSubtitles.localSubtitles(uri))

        scheduler.remove(item.fileName)
        withTimeout(5_000) {
            while (DownloadSubtitles.localSubtitles(uri).isNotEmpty()) delay(10)
        }
    }

    @Test
    fun unavailableAndInvalidSubtitlesDoNotFailTheVideo(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/valid.srt" -> MockResponse().setBody(srt)
                    "/invalid.srt" -> MockResponse().setBody("<html>Access denied</html>")
                    "/video" -> MockResponse().setBody("video")
                    else -> MockResponse().setResponseCode(503)
                }
            }
            val scheduler = AndroidDownloadScheduler(RuntimeEnvironment.getApplication())
            val item = downloadItem(server.url("/video").toString()).copy(
                fileName = "partial-subtitles.mkv",
                subtitleRequests = listOf(SubtitleAddonRequest(server.url("/failed-addon").toString(), "failed", "Failed addon")),
                sourceSubtitles = listOf("valid", "invalid", "missing").map {
                    StreamSubtitle(server.url("/$it.srt").toString(), "en", it)
                },
            )
            val transfer = scheduler.store.begin(item)
            assertFalse(scheduler.execute(transfer) { })
            val completed = assertNotNull(scheduler.store.get(item.fileName)).item
            assertEquals(DownloadStatus.Completed, completed.status)
            assertEquals(listOf("valid"), DownloadSubtitles.localSubtitles(completed.localFileUri!!).map { it.name })
        }
    }

    @Test
    fun savedSubtitlesFollowVideoDirectoryMovesAndIgnoreMissingFiles(): Unit = runBlocking {
        val oldDirectory = temporary.newFolder("old")
        val video = File(oldDirectory, "movie.mkv")
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(srt))
            val item = downloadItem().copy(sourceSubtitles = listOf(StreamSubtitle(server.url("/sub").toString(), "en")))
            DownloadSubtitles.prepare(item, video.toURI().toString())
        }
        val newDirectory = File(temporary.root, "new")
        assertTrue(oldDirectory.renameTo(newDirectory))
        val movedUri = File(newDirectory, video.name).toURI().toString()
        val track = DownloadSubtitles.localSubtitles(movedUri).single()
        assertEquals(srt, File(URI(track.url)).readText())
        File(URI(track.url)).delete()
        assertTrue(DownloadSubtitles.localSubtitles(movedUri).isEmpty())
    }

    @Test
    @Config(shadows = [HostFileProviderShadow::class], instrumentedPackages = ["androidx.core.content"])
    fun externalPlayersReceiveReadableLocalSubtitlesWithoutAddonRequests(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val provider = ProviderInfo().apply {
            authority = "${context.packageName}.fileprovider"
            name = FileProvider::class.java.name
            packageName = context.packageName
            applicationInfo = context.applicationInfo
            grantUriPermissions = true
            metaData = Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.file_paths) }
        }
        shadowOf(context.packageManager).addOrUpdateProvider(provider)
        SubtitleFileCache.initialize(context)
        val uri = File(temporary.newFolder(), "movie.mkv").toURI().toString()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(srt))
            DownloadSubtitles.prepare(
                downloadItem().copy(sourceSubtitles = listOf(StreamSubtitle(server.url("/en.srt").toString(), "en", "English"))),
                uri,
            )
        }
        val permanentTrack = DownloadSubtitles.localSubtitles(uri).single()
        val request = withTimeout(2_000) {
            prepareExternalPlayerLaunch(
                request = ExternalPlayerPlaybackRequest(uri, "Offline movie"),
                type = "movie",
                videoId = "offline",
                forwardSubtitles = true,
                sendSkipSegments = false,
                preferredLanguage = "en",
                secondaryLanguage = null,
                onOverlayMessage = {},
            )
        }
        val forwarded = assertNotNull(request.subtitles).single()
        assertTrue(forwarded.url.startsWith("content://"),
            "Local subtitle was not shared: " + ShadowLog.getLogsForTag("SubtitleFileCache").joinToString { "${it.msg}: ${it.throwable}" })
        assertEquals(srt, File(context.cacheDir, "subtitles/en_English.srt").readText())
        SubtitleFileCache.clearCache()
        assertEquals(srt, File(URI(permanentTrack.url)).readText())
    }

    @Test
    fun offlinePlaybackWithoutSavedSubtitlesDoesNotQueryInstalledAddons(): Unit = runBlocking {
        AddonStorage.initialize(RuntimeEnvironment.getApplication())
        AddonRepository.clearLocalState()
        SubtitleRepository.clear()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/manifest.json") {
                    MockResponse().setBody("""{"id":"offline-test","name":"Subtitle test","version":"1.0.0","resources":["subtitles"],"types":["movie"],"catalogs":[]}""")
                } else {
                    MockResponse().setBody("""{"subtitles":[]}""")
                }
            }
            try {
                assertIs<AddAddonResult.Success>(AddonRepository.addAddon(server.url("/manifest.json").toString()))
                assertEquals("/manifest.json", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).path)
                val request = withTimeout(2_000) {
                    prepareExternalPlayerLaunch(
                        request = ExternalPlayerPlaybackRequest(File(temporary.newFolder(), "offline.mkv").toURI().toString(), "Offline movie"),
                        type = "movie", videoId = "offline", forwardSubtitles = true,
                        sendSkipSegments = false, preferredLanguage = "en", secondaryLanguage = null,
                        onOverlayMessage = {},
                    )
                }
                assertNull(request.subtitles)
                assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS), "Offline playback must not query subtitle addons")
            } finally {
                AddonRepository.clearLocalState()
                SubtitleRepository.clear()
            }
        }
    }

    @Test
    fun subtitleSourcesSurvivePersistenceAndLegacyDownloadsStillDecode() {
        val legacy = downloadItem()
        val item = legacy.copy(
            subtitleRequests = listOf(SubtitleAddonRequest("https://example.com/subtitles/movie/tt1.json", "addon", "Addon")),
            sourceSubtitles = listOf(StreamSubtitle("https://example.com/en.srt", "en", headers = mapOf("Authorization" to "subtitle"))),
        )
        assertEquals(item, Json.decodeFromString<DownloadItem>(Json.encodeToString(item)))
        val decodedLegacy = Json.decodeFromString<DownloadItem>(Json.encodeToString(legacy))
        assertTrue(decodedLegacy.subtitleRequests.isEmpty())
        assertTrue(decodedLegacy.sourceSubtitles.isEmpty())
    }

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"
}

/**
 * AndroidX FileProvider compares canonical paths with an Android '/' separator,
 * which cannot match Windows host paths in Robolectric. Emulate only URI sharing
 * here; the subtitle is still copied/read normally and checked against the actual
 * configured cache root. Real Android FileProvider integration remains a device check.
 */
@Implements(value = FileProvider::class, isInAndroidSdk = false)
class HostFileProviderShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun getUriForFile(context: Context, authority: String, file: File): Uri {
            val provider = requireNotNull(context.packageManager.resolveContentProvider(authority, 128))
            val paths = requireNotNull(provider.loadXmlMetaData(context.packageManager, "android.support.FILE_PROVIDER_PATHS"))
            paths.use { parser ->
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG || parser.name != "cache-path") continue
                    val name = requireNotNull(parser.getAttributeValue(null, "name"))
                    val path = parser.getAttributeValue(null, "path").orEmpty()
                    val root = File(context.cacheDir, path).canonicalFile.toPath()
                    val target = file.canonicalFile.toPath()
                    if (!target.startsWith(root) || target == root) continue
                    val relative = root.relativize(target).toString().replace(File.separatorChar, '/')
                    return Uri.Builder().scheme("content").authority(authority).appendPath(name)
                        .appendEncodedPath(relative).build()
                }
            }
            error("File is outside the provider's configured cache roots")
        }
    }
}
