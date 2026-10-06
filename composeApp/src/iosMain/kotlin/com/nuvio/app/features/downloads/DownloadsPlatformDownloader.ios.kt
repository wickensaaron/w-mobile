package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.convert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import nuvio.composeapp.generated.resources.downloads_error_finalize_file_failed
import nuvio.composeapp.generated.resources.downloads_error_open_partial_file_failed
import nuvio.composeapp.generated.resources.downloads_error_partial_file_not_open
import nuvio.composeapp.generated.resources.downloads_error_write_partial_file_failed
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSError
import platform.Foundation.NSDate
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite

private const val DOWNLOAD_REQUEST_TIMEOUT_SECONDS = 60.0
private const val DOWNLOAD_RESOURCE_TIMEOUT_SECONDS = 24.0 * 60.0 * 60.0
private const val PROGRESS_MIN_INTERVAL_SECONDS = 0.5
private const val PROGRESS_MIN_BYTE_DELTA = 512L * 1024L
private const val MAX_VALIDATOR_LENGTH = 256

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsPlatformDownloader {
    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle = IosBackgroundDownloadsBridge.start(
        request = request,
        onProgress = onProgress,
        onSuccess = onSuccess,
        onFailure = onFailure,
    )

    actual fun restoreItem(item: DownloadItem): DownloadItem =
        IosBackgroundDownloadsBridge.restore(item)

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val path = localFileUri.toLocalPath() ?: return false
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            return removePathIfExists(path)
        }

        val fileName = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false
        return removePathIfExists("${downloadsDirectoryPath()}/$fileName")
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        NSNotificationCenter.defaultCenter.postNotificationName(discardNotification, destinationFileName)
        val destinationPath = "${downloadsDirectoryPath()}/$destinationFileName"
        DownloadSubtitleStorage(NSURL.fileURLWithPath(destinationPath).absoluteString!!).remove()
        val partialPath = "$destinationPath.part"
        return removePathIfExists(partialPath) && removePathIfExists("$partialPath.validator")
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri?.toLocalPath()
            ?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?.let { path ->
                return NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
            }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalPath()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        val currentPath = "${downloadsDirectoryPath()}/$fileName"
        return if (NSFileManager.defaultManager.fileExistsAtPath(currentPath)) {
            NSURL.fileURLWithPath(currentPath).absoluteString ?: "file://$currentPath"
        } else {
            null
        }
    }

    actual fun openDownloadsDirectory(): Boolean {
        val url = NSURL.fileURLWithPath(downloadsDirectoryPath())
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
        return true
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readResumeValidator(partialPath: String): IosResumeValidator? {
    val stored = NSString.stringWithContentsOfFile("$partialPath.validator", NSUTF8StringEncoding, null)
        ?.toString() ?: return null
    val separator = stored.indexOf(':')
    if (separator < 1) return null
    val header = stored.substring(0, separator)
    val value = stored.substring(separator + 1)
    return when (header) {
        "ETag" -> iosResumeValidator(value)
        else -> null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun saveResumeValidator(partialPath: String, validator: IosResumeValidator?) {
    val path = "$partialPath.validator"
    if (validator == null) {
        check(removePathIfExists(path))
    } else {
        check(NSString.create(string = "${validator.header}:${validator.value}")
            .writeToFile(path, true, NSUTF8StringEncoding, null))
    }
}

private class IosDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    private var task: NSURLSessionTask? = null
    private var session: NSURLSession? = null

    fun attach(task: NSURLSessionTask, session: NSURLSession) {
        this.task = task
        this.session = session
    }

    override fun cancel() {
        cancelNativeTask()
        job.cancel()
    }

    fun cancelNativeTask() {
        task?.cancel()
        session?.invalidateAndCancel()
        task = null
        session = null
    }
}

private data class IosDownloadResult(
    val statusCode: Int,
    val expectedTotalBytes: Long?,
)

internal data class IosResumeValidator(val header: String, val value: String)

internal data class IosDownloadRange(val start: Long, val end: Long, val total: Long)

internal fun parseIosDownloadRange(header: String?): IosDownloadRange? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
        .matchEntire(header?.trim().orEmpty()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    return IosDownloadRange(start, end, total).takeIf {
        it.start >= 0L && it.end >= it.start && it.total > it.end
    }
}

internal fun iosResumeValidator(etag: String?): IosResumeValidator? {
    fun safe(value: String?): String? = value?.trim()?.takeIf {
        it.isNotEmpty() && it.length <= MAX_VALIDATOR_LENGTH && it.none { c -> c.code < 32 || c.code == 127 }
    }
    safe(etag)?.takeUnless { it.startsWith("W/", ignoreCase = true) }
        ?.let { return IosResumeValidator("ETag", it) }
    return null
}

internal fun validateIosDownloadResponse(
    status: Int,
    requestedOffset: Long,
    previousValidator: IosResumeValidator?,
    responseValidator: IosResumeValidator?,
    contentRange: String?,
    contentLength: String?,
    contentType: String?,
): Long? {
    if (status != 200 && status != 206) throw IllegalStateException("Invalid download response")
    val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (type.isNotEmpty() && !(
            type.startsWith("video/") || type.startsWith("audio/") ||
                type in setOf(
                    "application/octet-stream", "application/mp4", "application/x-matroska",
                    "application/ogg", "application/vnd.ms-asf", "application/mp2t",
                    "application/x-mpeg-ts", "application/x-msvideo", "application/x-flv",
                )
        )
    ) throw IllegalStateException("Response is not a direct media file")
    val length = contentLength?.trim()?.toLongOrNull()
    if (contentLength != null && (length == null || length <= 0L)) {
        throw IllegalStateException("Invalid download length")
    }
    if (status == 200) {
        if (contentRange != null) throw IllegalStateException("Unexpected download range")
        return length
    }
    val range = parseIosDownloadRange(contentRange)
        ?: throw IllegalStateException("Invalid download range")
    if (requestedOffset <= 0L || previousValidator == null || responseValidator != previousValidator ||
        range.start != requestedOffset || range.end != range.total - 1L ||
        (length != null && length != range.end - range.start + 1L)
    ) throw IllegalStateException("Download source changed during resume")
    return range.total
}

@OptIn(ExperimentalForeignApi::class)
private class IosDownloadDelegate(
    private val attemptedRangeRequest: Boolean,
    private val resumeFromBytes: Long,
    private val resumeValidator: IosResumeValidator?,
    private val tempPath: String,
    private val onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    private val completion = CompletableDeferred<IosDownloadResult>()
    private var result: IosDownloadResult? = null
    private var fileError: Throwable? = null
    private var outputFile: CPointer<FILE>? = null
    private var startingBytesForResponse = 0L
    private var bytesWrittenForResponse = 0L
    private var totalBytesForResponse: Long? = null
    private var lastProgressBytes = -1L
    private var lastProgressTimestampSeconds = 0.0

    suspend fun awaitCompletion(): IosDownloadResult = completion.await()

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (Long) -> Unit,
    ) {
        try {
            val httpResponse = didReceiveResponse as? NSHTTPURLResponse
                ?: throw IllegalStateException("Invalid download response")
            val statusCode = httpResponse.statusCode.toInt()
            if (statusCode == 416 && attemptedRangeRequest) {
                result = IosDownloadResult(statusCode, null)
                completionHandler(1L)
                return
            }
            if (statusCode != 200 && statusCode != 206) {
                result = IosDownloadResult(statusCode, null)
                completionHandler(1L)
                return
            }
            val responseValidator = iosResumeValidator(
                httpResponse.valueForHTTPHeaderField("ETag"),
            )
            val total = validateIosDownloadResponse(
                status = statusCode,
                requestedOffset = if (attemptedRangeRequest) resumeFromBytes else 0L,
                previousValidator = resumeValidator,
                responseValidator = responseValidator,
                contentRange = httpResponse.valueForHTTPHeaderField("Content-Range"),
                contentLength = httpResponse.valueForHTTPHeaderField("Content-Length"),
                contentType = httpResponse.valueForHTTPHeaderField("Content-Type"),
            )
            startingBytesForResponse = if (statusCode == 206) resumeFromBytes else 0L
            bytesWrittenForResponse = 0L
            totalBytesForResponse = total
            outputFile = fopen(tempPath, if (statusCode == 206) "ab" else "wb")
                ?: throw IllegalStateException(runBlocking { getString(Res.string.downloads_error_open_partial_file_failed) })
            saveResumeValidator(tempPath, responseValidator)
            result = IosDownloadResult(statusCode, total)
            reportProgress(startingBytesForResponse, total)
            completionHandler(1L)
        } catch (error: Throwable) {
            fileError = error
            completionHandler(0L)
        }
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData,
    ) {
        val responseStatus = result?.statusCode
        if (fileError != null || (responseStatus != 200 && responseStatus != 206)) return

        val file = outputFile ?: run {
            fileError = IllegalStateException(runBlocking { getString(Res.string.downloads_error_partial_file_not_open) })
            return
        }

        val bytesToWrite = didReceiveData.length.toLong()
        val wrote = fwrite(
            didReceiveData.bytes,
            1.convert(),
            bytesToWrite.convert(),
            file,
        ).toLong()
        if (wrote != bytesToWrite) {
            fileError = IllegalStateException(runBlocking { getString(Res.string.downloads_error_write_partial_file_failed) })
            return
        }
        fflush(file)

        bytesWrittenForResponse += bytesToWrite
        reportProgress(
            downloadedBytes = startingBytesForResponse + bytesWrittenForResponse,
            totalBytes = totalBytesForResponse,
        )
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        closeOutputFile()

        val error = fileError
        if (error != null) {
            completion.completeExceptionally(error)
            return
        }
        if (didCompleteWithError != null) {
            completion.completeExceptionally(
                IllegalStateException("Download connection failed"),
            )
            return
        }
        val finished = result
        if (finished == null) completion.completeExceptionally(IllegalStateException("Missing download response"))
        else completion.complete(finished)
    }

    private fun closeOutputFile() {
        outputFile?.let { file ->
            fflush(file)
            fclose(file)
        }
        outputFile = null
    }

    private fun reportProgress(
        downloadedBytes: Long,
        totalBytes: Long?,
    ) {
        val normalizedDownloadedBytes = downloadedBytes.coerceAtLeast(0L)
        val now = NSDate().timeIntervalSince1970
        val byteDelta = normalizedDownloadedBytes - lastProgressBytes
        val timeDelta = now - lastProgressTimestampSeconds
        val reachedEnd = totalBytes != null && normalizedDownloadedBytes >= totalBytes

        if (
            lastProgressBytes >= 0L &&
            !reachedEnd &&
            byteDelta < PROGRESS_MIN_BYTE_DELTA &&
            timeDelta < PROGRESS_MIN_INTERVAL_SECONDS
        ) {
            return
        }

        lastProgressBytes = normalizedDownloadedBytes
        lastProgressTimestampSeconds = now
        onProgress(normalizedDownloadedBytes, totalBytes)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun downloadsDirectoryPath(): String {
    val root = NSHomeDirectory().trimEnd('/')
    val path = "$root/Documents/nuvio_downloads"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return path
}

@OptIn(ExperimentalForeignApi::class)
private fun removePathIfExists(path: String): Boolean {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return true
    return NSFileManager.defaultManager.removeItemAtPath(path, null)
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun performDownloadRequest(
    request: DownloadPlatformRequest,
    rangeStart: Long?,
    resumeValidator: IosResumeValidator?,
    resumeFromBytes: Long,
    tempPath: String,
    handle: IosDownloadsTaskHandle,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): IosDownloadResult {
    val url = NSURL(string = request.sourceUrl)
    val nativeRequest = NSMutableURLRequest(
        uRL = url,
        cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
        timeoutInterval = DOWNLOAD_REQUEST_TIMEOUT_SECONDS,
    )
    nativeRequest.setHTTPMethod("GET")
    nativeRequest.setAllowsCellularAccess(true)
    nativeRequest.setAllowsExpensiveNetworkAccess(true)
    nativeRequest.setAllowsConstrainedNetworkAccess(true)
    request.sourceHeaders.forEach { (key, value) ->
        if (key.lowercase() !in setOf("range", "if-range", "accept-encoding")) {
            nativeRequest.setValue(value, forHTTPHeaderField = key)
        }
    }
    nativeRequest.setValue("identity", forHTTPHeaderField = "Accept-Encoding")
    if (rangeStart != null && rangeStart > 0L) {
        nativeRequest.setValue("bytes=$rangeStart-", forHTTPHeaderField = "Range")
        checkNotNull(resumeValidator)
        nativeRequest.setValue(resumeValidator.value, forHTTPHeaderField = "If-Range")
    }

    val delegate = IosDownloadDelegate(
        attemptedRangeRequest = rangeStart != null && rangeStart > 0L,
        resumeFromBytes = resumeFromBytes,
        resumeValidator = resumeValidator,
        tempPath = tempPath,
        onProgress = onProgress,
    )
    val configuration = NSURLSessionConfiguration.defaultSessionConfiguration().apply {
        timeoutIntervalForRequest = DOWNLOAD_REQUEST_TIMEOUT_SECONDS
        timeoutIntervalForResource = DOWNLOAD_RESOURCE_TIMEOUT_SECONDS
        waitsForConnectivity = true
        allowsCellularAccess = true
        allowsExpensiveNetworkAccess = true
        allowsConstrainedNetworkAccess = true
    }
    val session = NSURLSession.sessionWithConfiguration(
        configuration = configuration,
        delegate = delegate,
        delegateQueue = NSOperationQueue().apply {
            maxConcurrentOperationCount = 1
        },
    )
    val task = session.dataTaskWithRequest(nativeRequest)

    handle.attach(task, session)
    return try {
        currentCoroutineContext().ensureActive()
        onProgress(resumeFromBytes.coerceAtLeast(0L), null)
        task.resume()
        delegate.awaitCompletion()
    } finally {
        if (currentCoroutineContext().isActive) session.finishTasksAndInvalidate()
        else session.invalidateAndCancel()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fileSizeOrNull(path: String): Long? {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
    val value = attrs?.get("NSFileSize")
    return when (value) {
        is Long -> value
        is Number -> value.toLong()
        else -> null
    }
}

private fun String.toLocalPath(): String? {
    val value = trim()
    if (value.startsWith("file:")) {
        return NSURL(string = value).path ?: value.removePrefix("file://")
    }
    return value.takeIf { it.isNotBlank() }
}
