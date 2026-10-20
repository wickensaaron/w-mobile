package com.nuvio.app.features.player

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddonSubtitleDiscoveryTest {
    @Test fun oneFailingAddonDoesNotHideAnotherAddonsResults() = runTest {
        val failed = mutableListOf<String>()
        val requests = listOf("failed", "working").map { SubtitleAddonRequest(it, it, it) }
        val results = loadAddonSubtitles(requests, fetchResponse = { url ->
            if (url == "failed") error("unavailable")
            """{"subtitles":[{"id":"en","url":"https://example.invalid/en.srt","lang":"eng"}]}"""
        }, onFailed = { failed += it.addonId })
        assertEquals(listOf("failed"), failed)
        assertEquals(listOf("en"), results.map { it.id })
    }

    @Test fun timedOutAddonIsReportedAsFailureRatherThanNoMatches() = runTest {
        var failed = false
        val results = loadAddonSubtitles(listOf(SubtitleAddonRequest("slow", "slow", "slow")),
            fetchResponse = { delay(11_000); """{"subtitles":[]}""" }, onFailed = { failed = true })
        assertTrue(failed)
        assertTrue(results.isEmpty())
    }

    @Test fun malformedSubtitleRowDoesNotDiscardValidResults() = runTest {
        val result = parseAddonSubtitles("""{"subtitles":[
            {"url":{"invalid":true},"lang":"eng"},
            {"id":"valid","url":"https://example.invalid/en.srt","lang":"eng"}
        ]}""", SubtitleAddonRequest("https://example.invalid/subtitles", "test", "Test"))
        assertEquals(listOf("valid"), result.map { it.id })
        assertEquals(listOf("en"), result.map { it.language })
    }
}
