package com.nuvio.app.features.livetv

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvGuideProjectionTest {
    private val owner = LiveTvAccountScope("https://account.example.invalid", "account-a", 1)

    private fun channel(source: String, id: String, guideId: String) = LiveTvChannel(
        "$source:$id", "Channel $id", "https://stream.example.invalid/$id",
        playlistId = source, guideId = guideId, accountScope = owner,
    )

    private fun row(id: String, title: String = id) = LiveTvProgramme(id, title, startEpochMs = 1, stopEpochMs = 2)

    @Test
    fun `exact guide spelling wins and fallback uses the first case insensitive entry within one source`() = runTest {
        val projection = LiveTvGuideProjection()
        projection.appendImported("source-a", listOf(
            channel("source-a", "1", "BBC"), channel("source-a", "2", "BbC"),
            channel("source-b", "3", "BBC"), channel("source-a", "4", "unmatched"),
        ), linkedMapOf("bbc" to listOf(row("bbc", "First")), "BBC" to listOf(row("BBC", "Exact"))))
        val result = projection.snapshot()
        assertEquals(setOf("source-a:1", "source-a:2"), result.programmes.keys)
        assertEquals("Exact", result.programmes.getValue("source-a:1").single().title)
        assertEquals("First", result.programmes.getValue("source-a:2").single().title)
        assertTrue(result.programmes.all { (key, rows) -> rows.all { it.channelId == key } })
    }

    @Test
    fun `large missing channel set indexes guide entries only once`() = runTest {
        val backing = mapOf("known" to listOf(row("known")))
        var entryReads = 0
        val parsed = object : Map<String, List<LiveTvProgramme>> by backing {
            override val entries: Set<Map.Entry<String, List<LiveTvProgramme>>>
                get() { entryReads++; return backing.entries }
        }
        val projection = LiveTvGuideProjection()
        projection.appendImported("source-a", List(1_000) { channel("source-a", "$it", "missing-$it") }, parsed)
        assertEquals(1, entryReads)
        assertTrue(projection.snapshot().programmes.isEmpty())
    }

    @Test
    fun `manual and multiple provider rows share a budget checked before any extra row reads or copies`() = runTest {
        var reads = 0
        val oversized = object : AbstractList<LiveTvProgramme>() {
            override val size = 100_000
            override fun get(index: Int): LiveTvProgramme {
                reads++
                check(index == 0) { "Rows past the remaining budget must never be read" }
                return row("guide")
            }
        }
        val projection = LiveTvGuideProjection(limit = 2)
        projection.appendManual(mapOf("manual" to listOf(row("manual"))))
        projection.appendImported("source-a", listOf(channel("source-a", "1", "guide")), mapOf("guide" to oversized))
        projection.appendImported("source-b", listOf(channel("source-b", "1", "guide")), mapOf("guide" to oversized))
        val result = projection.snapshot()
        assertEquals(1, reads)
        assertEquals(2, result.programmeCount)
        assertEquals(2, result.programmes.values.sumOf { it.size })
        assertEquals(setOf("manual", "source-a:1"), result.programmes.keys)
        assertTrue(result.wasTruncated)
        assertFalse(projection.hasCapacity)
    }

    @Test
    fun `published guide snapshot cannot change with the input list or a later builder append`() = runTest {
        val input = mutableListOf(row("first"))
        val projection = LiveTvGuideProjection()
        projection.appendManual(mapOf("first" to input))
        val result = projection.snapshot()
        input.clear()
        projection.appendManual(mapOf("later" to listOf(row("later"))))
        assertEquals(setOf("first"), result.programmes.keys)
        assertEquals(listOf(row("first")), result.programmes.getValue("first"))
    }

    @Test
    fun `cancelling guide projection stops before publication`() = runTest {
        var published = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            val projection = LiveTvGuideProjection()
            projection.appendImported("source-a", List(1_000) { channel("source-a", "$it", "guide") },
                mapOf("guide" to listOf(row("guide"))))
            projection.snapshot()
            published = true
        }
        job.cancel()
        job.join()
        assertFalse(published)
    }
}
