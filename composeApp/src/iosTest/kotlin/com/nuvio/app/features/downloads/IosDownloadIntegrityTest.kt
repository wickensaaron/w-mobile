package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IosDownloadIntegrityTest {
    private val etag = iosResumeValidator("\"v2\"")!!

    @Test
    fun rangeMustDescribeTheExactRemainingFile() {
        assertEquals(IosDownloadRange(500, 999, 1000), parseIosDownloadRange("bytes 500-999/1000"))
        for (value in listOf("bytes 499-999/1000", "bytes 500-998/1000", "bytes 500-999/*",
            "bytes 500-1000/1000", "bytes 0-1/0", "bytes 999-500/1000", "bytes x-y/z",
            "bytes 9223372036854775808-999/1000")) {
            assertFailsWith<IllegalStateException> {
                validateIosDownloadResponse(206, 500, etag, etag, value, "500", "video/mp4")
            }
        }
    }

    @Test
    fun resumeRequiresMatchingValidatorAndLength() {
        assertEquals(1000L, validateIosDownloadResponse(206, 500, etag, etag,
            "bytes 500-999/1000", "500", "video/mp4"))
        assertFailsWith<IllegalStateException> {
            validateIosDownloadResponse(206, 500, etag, iosResumeValidator("\"v3\""),
                "bytes 500-999/1000", "500", "video/mp4")
        }
        assertFailsWith<IllegalStateException> {
            validateIosDownloadResponse(206, 500, etag, etag,
                "bytes 500-999/1000", "499", "video/mp4")
        }
        assertFailsWith<IllegalStateException> {
            validateIosDownloadResponse(206, 0, null, etag,
                "bytes 0-999/1000", "1000", "video/mp4")
        }
    }

    @Test
    fun fullRestartAndMediaTypeAreChecked() {
        assertEquals(1000L, validateIosDownloadResponse(200, 500, etag, null,
            null, "1000", "application/octet-stream"))
        assertNull(validateIosDownloadResponse(200, 0, null, null, null, null, "video/mp4"))
        for (type in listOf("text/html", "application/json", "application/vnd.apple.mpegurl")) {
            assertFailsWith<IllegalStateException> {
                validateIosDownloadResponse(200, 0, null, null, null, "1000", type)
            }
        }
        assertFailsWith<IllegalStateException> {
            validateIosDownloadResponse(204, 0, null, null, null, null, null)
        }
    }

    @Test
    fun onlyStrongBoundedValidatorsAllowResume() {
        assertNull(iosResumeValidator("W/\"weak\""))
        assertNull(iosResumeValidator("a\r\nb"))
        assertNull(iosResumeValidator("x".repeat(257)))
    }
}
