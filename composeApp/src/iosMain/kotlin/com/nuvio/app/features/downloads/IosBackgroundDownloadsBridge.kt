package com.nuvio.app.features.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL

private const val startNotification = "WMediaBackgroundDownloadStart"
private const val cancelNotification = "WMediaBackgroundDownloadCancel"
private const val pauseNotification = "WMediaBackgroundDownloadPause"
internal const val discardNotification = "WMediaBackgroundDownloadDiscard"

@Serializable
private data class BackgroundDownloadStart(
    val id: String,
    val url: String,
    val fileName: String,
    val headers: Map<String, String>,
)

private data class BackgroundDownloadCallbacks(
    val onProgress: (Long, Long?) -> Unit,
    val onSuccess: (String, Long?) -> Unit,
    val onFailure: (String) -> Unit,
)

/** The native app owns the background URLSession and calls these exported functions on the main queue. */
@OptIn(ExperimentalForeignApi::class)
internal object IosBackgroundDownloadsBridge {
    private val callbacks = mutableMapOf<String, BackgroundDownloadCallbacks>()
    private val subtitleJobs = mutableMapOf<String, Job>()
    private val subtitleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json

    fun start(
        request: DownloadPlatformRequest,
        onProgress: (Long, Long?) -> Unit,
        onSuccess: (String, Long?) -> Unit,
        onFailure: (String) -> Unit,
    ): DownloadsTaskHandle {
        val id = request.item.id
        callbacks[id] = BackgroundDownloadCallbacks(onProgress, onSuccess, onFailure)
        val destination = NSURL.fileURLWithPath(
            downloadsDirectoryPathForBackground() + "/" + request.destinationFileName,
        ).absoluteString.orEmpty()
        subtitleJobs.remove(id)?.cancel()
        subtitleJobs[id] = subtitleScope.launch {
            runCatching { DownloadSubtitles.prepare(request.item, destination) }
        }
        val payload = json.encodeToString(
            BackgroundDownloadStart(
                id = id,
                url = request.sourceUrl,
                fileName = request.destinationFileName,
                headers = request.sourceHeaders,
            ),
        )
        NSNotificationCenter.defaultCenter.postNotificationName(startNotification, payload)
        return object : DownloadsTaskHandle {
            override fun cancel() {
                callbacks.remove(id)
                subtitleJobs.remove(id)?.cancel()
                NSNotificationCenter.defaultCenter.postNotificationName(cancelNotification, id)
            }

            override fun pause() {
                callbacks.remove(id)
                subtitleJobs.remove(id)?.cancel()
                NSNotificationCenter.defaultCenter.postNotificationName(pauseNotification, id)
            }
        }
    }

    fun restore(item: DownloadItem): DownloadItem {
        val path = downloadsDirectoryPathForBackground() + "/" + item.fileName
        val size = fileSizeForBackground(path)
        if (size != null && size > 0L && item.status != DownloadStatus.Completed) {
            return item.copy(
                status = DownloadStatus.Completed,
                localFileUri = NSURL.fileURLWithPath(path).absoluteString,
                downloadedBytes = size,
                totalBytes = size,
                errorMessage = null,
            )
        }
        // A background URLSession task survives process suspension and system termination.
        // Reattaching on launch determines whether it is still running or must restart.
        return item
    }

    fun progress(id: String, downloadedBytes: Long, totalBytes: Long) {
        callbacks[id]?.onProgress(downloadedBytes, totalBytes.takeIf { it > 0L })
    }

    fun finished(id: String, localFileUri: String, totalBytes: Long) {
        subtitleJobs.remove(id)?.cancel()
        callbacks.remove(id)?.onSuccess(localFileUri, totalBytes.takeIf { it > 0L })
    }

    fun failed(id: String) {
        subtitleJobs.remove(id)?.cancel()
        callbacks.remove(id)?.onFailure("Download failed. Try again from Downloads.")
    }
}

/** Called by the Swift URLSession delegate. Never include source URLs in these callbacks. */
fun iosBackgroundDownloadProgress(id: String, downloadedBytes: Long, totalBytes: Long) =
    IosBackgroundDownloadsBridge.progress(id, downloadedBytes, totalBytes)

fun iosBackgroundDownloadFinished(id: String, localFileUri: String, totalBytes: Long) =
    IosBackgroundDownloadsBridge.finished(id, localFileUri, totalBytes)

fun iosBackgroundDownloadFailed(id: String) = IosBackgroundDownloadsBridge.failed(id)

@OptIn(ExperimentalForeignApi::class)
private fun downloadsDirectoryPathForBackground(): String {
    val path = platform.Foundation.NSHomeDirectory().trimEnd('/') + "/Documents/nuvio_downloads"
    NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
    return path
}

@OptIn(ExperimentalForeignApi::class)
private fun fileSizeForBackground(path: String): Long? {
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
    return (attributes?.get("NSFileSize") as? Number)?.toLong()
}
