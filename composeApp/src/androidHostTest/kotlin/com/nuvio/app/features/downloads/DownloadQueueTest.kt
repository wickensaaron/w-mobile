package com.nuvio.app.features.downloads

import android.net.NetworkCapabilities
import com.nuvio.app.features.streams.StreamItem
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadQueueTest {
    @Test fun wifiOnlyTransfersUseAnUnmeteredSystemConstraint() {
        val scheduler = AndroidDownloadScheduler(RuntimeEnvironment.getApplication())
        val transfer = scheduler.store.begin(downloadItem(id = "wifi").copy(fileName = "wifi.mkv", wifiOnly = true))
        val required = assertNotNull(scheduler.buildJob(transfer).requiredNetwork)
        assertTrue(required.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    @Test fun onlyTwoTransfersReadFromTheNetworkWhileAnotherRemainsQueued(): Unit = runBlocking {
        val received = CountDownLatch(2)
        val release = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    received.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return MockResponse().setBody("complete video")
                }
            }
            val scheduler = AndroidDownloadScheduler(RuntimeEnvironment.getApplication())
            val transfers = (1..3).map { number -> scheduler.store.begin(downloadItem(server.url("/$number").toString(), "queue-$number")
                .copy(fileName = "queue-$number.mkv")) }
            val jobs = transfers.map { transfer -> async(Dispatchers.IO) { scheduler.execute(transfer) { } } }
            try {
                assertTrue(withContext(Dispatchers.IO) { received.await(5, TimeUnit.SECONDS) })
                assertEquals(2, server.requestCount)
                assertEquals(1, transfers.count { scheduler.store.get(it.item.fileName)?.item?.isQueued == true })
            } finally { release.countDown() }
            withTimeout(10_000) { jobs.awaitAll() }
            assertEquals(3, server.requestCount)
            assertTrue(transfers.all { scheduler.store.get(it.item.fileName)?.item?.status == DownloadStatus.Completed })
        }
    }

    @Test fun selectingAnExistingDownloadCannotDeleteOrReplaceItsFile() {
        val context = RuntimeEnvironment.getApplication()
        DownloadsRepository.clearLocalState()
        DownloadsStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        val savedFile = File(context.cacheDir, "saved-copy.mkv").apply { writeText("saved content") }
        val existing = downloadItem(id = "existing").copy(status = DownloadStatus.Completed, localFileUri = savedFile.toURI().toString())
        DownloadsStorage.savePayload("{\"items\":[${Json.encodeToString(existing)}]}")
        try {
            val result = DownloadsRepository.enqueueFromStream(existing.contentType, existing.videoId, existing.parentMetaId,
                existing.parentMetaType, existing.title, null, null, null, existing.seasonNumber, existing.episodeNumber, null, null,
                StreamItem(url = "https://example.invalid/new.mkv", addonId = "test", addonName = "Test"))
            assertEquals(DownloadEnqueueResult.AlreadyExists, result)
            assertEquals("saved content", savedFile.readText())
            assertEquals(existing.sourceUrl, DownloadsRepository.uiState.value.items.single().sourceUrl)
        } finally {
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload("{\"items\":[]}")
            savedFile.delete()
        }
    }
}
