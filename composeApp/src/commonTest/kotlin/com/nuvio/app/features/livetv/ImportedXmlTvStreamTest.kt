package com.nuvio.app.features.livetv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.io.encoding.Base64

class ImportedXmlTvStreamTest {
    private val owner = LiveTvAccountScope("https://account.example.invalid", "account-a", 1)
    private val now = 1_767_268_800_000L // 2026-01-01 12:00 UTC
    private fun channel(source: String = "source-a", id: String = "1", key: String = "bbc.uk") = LiveTvChannel(
        "$source:$id", "BBC", "https://stream.example.invalid/$id", playlistId = source,
        guideId = key, accountScope = owner,
    )
    private fun programme(key: String = "bbc.uk", title: String = "News", from: String = "20260101120000 +0000",
        stop: String = "20260101130000 +0000", description: String = "") =
        "<programme channel=\"$key\" start=\"$from\" stop=\"$stop\"><title>$title</title><desc>$description</desc></programme>"

    private fun reader(text: String, chunk: Int = 17): suspend (ByteArray) -> Int = reader(text.encodeToByteArray(), chunk)
    private fun reader(bytes: ByteArray, chunk: Int = 17): suspend (ByteArray) -> Int {
        var position = 0
        return { output ->
            if (position == bytes.size) -1 else {
                val count = minOf(chunk, output.size, bytes.size - position)
                bytes.copyInto(output, 0, position, position + count)
                position += count
                count
            }
        }
    }

    private fun ImportedXmlTvCollector.offer(key: String, title: String, hour: Int = 12) {
        startElement("programme", key, "20260101${hour.toString().padStart(2, '0')}0000 +0000",
            "20260101${(hour + 1).toString().padStart(2, '0')}0000 +0000")
        startElement("title", null, null, null)
        text(title)
        endElement("title")
        endElement("programme")
    }

    @Test
    fun `collector case collisions preserve first fallback independently for owned channels`() = runTest {
        val limits = ImportedXmlTvLimits()
        val collector = ImportedXmlTvCollector("source-a", listOf(channel(key = "BBC"),
            channel(id = "2", key = "BbC"), channel("source-b", "3", "BBC")), now,
            currentCoroutineContext(), { true }, limits)
        collector.startElement("tv", null, null, null)
        collector.offer("bbc", "First fallback")
        collector.offer("bBc", "Later fallback", 13)
        collector.offer("BBC1", "No numeric guessing", 14)
        collector.endElement("tv")
        val result = collector.snapshot()
        assertEquals(setOf("source-a:1", "source-a:2"), result.programmes.keys)
        assertEquals(listOf("First fallback"), result.programmes.getValue("source-a:1").map { it.title })
        assertEquals(listOf("First fallback"), result.programmes.getValue("source-a:2").map { it.title })
    }

    @Test
    fun `collector exact XML spelling dominates fallback in either arrival order`() = runTest {
        for (fallbackFirst in listOf(true, false)) {
            val limits = ImportedXmlTvLimits()
            val collector = ImportedXmlTvCollector("source-a", listOf(channel(key = "BBC"), channel(id = "2", key = "BbC")),
                now, currentCoroutineContext(), { true }, limits)
            collector.startElement("tv", null, null, null)
            if (fallbackFirst) collector.offer("bbc", "Fallback")
            collector.offer("BBC", "Exact upper", 13)
            collector.offer("BbC", "Exact mixed", 14)
            if (!fallbackFirst) collector.offer("bbc", "Fallback")
            collector.endElement("tv")
            val result = collector.snapshot()
            assertEquals(listOf("Exact upper"), result.programmes.getValue("source-a:1").map { it.title })
            assertEquals(listOf("Exact mixed"), result.programmes.getValue("source-a:2").map { it.title })
        }
    }

    @Test
    fun `collector exact replacement reclaims fallback row budget before retaining another channel`() = runTest {
        val limits = ImportedXmlTvLimits(retainedProgrammes = 2)
        val collector = ImportedXmlTvCollector("source-a", listOf(channel(key = "BBC"), channel(id = "2", key = "other")),
            now, currentCoroutineContext(), { true }, limits)
        collector.startElement("tv", null, null, null)
        collector.offer("bbc", "Fallback one")
        collector.offer("bbc", "Fallback two", 13)
        collector.offer("bbc", "Discarded fallback", 14)
        collector.offer("BBC", "Exact", 15)
        collector.offer("other", "Other", 16)
        collector.endElement("tv")
        val result = collector.snapshot()
        assertEquals(2, result.programmes.values.sumOf { it.size })
        assertEquals("Exact", result.programmes.getValue("source-a:1").single().title)
        assertEquals("Other", result.programmes.getValue("source-a:2").single().title)
        assertFalse(result.wasTruncated) // Discarded fallback no longer owns a budget warning.
    }

    @Test
    fun `raw gzip streams decode with split headers and reject a truncated trailer or decoded bomb budget`() = runTest {
        // Fixed gzip fixture of one XMLTV programme, generated from public synthetic test data.
        val gzip = Base64.decode("H4sIAAAAAAAACrMpKbOzKSjKTy9KzM1NVUjOSMzLS82xVUpKStYrzVZSKC5JLCqxVTIyMDIzMDQwNDQyMDAwUNAGkSDJ/AIkOWMkOTubksySnFQ7v9TyYht9CNtGH26RnY1+SZkdANBewjJ9AAAA")
        val limits = ImportedXmlTvLimits()
        val collector = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { true }, limits)
        val parsed = parseImportedXmlTvStream(reader(gzip, 1), collector, limits)
        assertEquals("News", parsed.programmes.getValue("source-a:1").single().title)
        val other = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { true }, limits)
        // Platform gzip validation may report its own IOException; neither adapter publishes rows.
        assertFailsWith<Exception> { parseImportedXmlTvStream(reader(gzip.copyOf(gzip.size - 5), 1), other, limits) }
        val small = limits.copy(decodedBytes = 32)
        val bounded = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { true }, small)
        assertFailsWith<IllegalStateException> { parseImportedXmlTvStream(reader(gzip), bounded, small) }
    }

    @Test
    fun `split UTF8 tags entities comments CDATA and external doctype parse without expansion`() = runTest {
        val limits = ImportedXmlTvLimits()
        val collector = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { true }, limits)
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE tv SYSTEM \"https://never-fetch.example.invalid/guide.dtd\">" +
            "<tv><!-- literal <!ENTITY text is harmless here -->" +
            programme(title = "Café &amp; News &#x1f4fa;", description = "<![CDATA[Details <with> literal <!ENTITY text]]>") + "</tv>"
        val parsed = parseImportedXmlTvStream(reader(xml, 1), collector, limits)
        val row = parsed.programmes.getValue("source-a:1").single()
        assertEquals("Café & News 📺", row.title)
        assertEquals("Details <with> literal <!ENTITY text", row.description)
        assertFalse(parsed.wasTruncated)
    }

    @Test
    fun `XML attribute entity decoding occurs before exact source key matching`() = runTest {
        val limits = ImportedXmlTvLimits()
        val collector = ImportedXmlTvCollector("source-a", listOf(channel(key = "bbc&.uk")), now,
            currentCoroutineContext(), { true }, limits)
        val parsed = parseImportedXmlTvStream(reader("<tv>" + programme(key = "bbc&amp;.uk") + "</tv>", 1), collector, limits)
        assertEquals("News", parsed.programmes.getValue("source-a:1").single().title)
    }

    @Test
    fun `XML attributes normalize once including numeric references and literal entity text identifiers`() = runTest {
        val cases = listOf(
            "bbc&amp;.uk" to "bbc&.uk", "bbc&#38;.uk" to "bbc&.uk", "bbc&#x26;.uk" to "bbc&.uk",
            "bbc&amp;amp;.uk" to "bbc&amp;.uk", "bbc&amp;#38;.uk" to "bbc&#38;.uk",
            "BBC&#x31;" to "BBC1",
        )
        for ((xmlKey, ownedKey) in cases) {
            val limits = ImportedXmlTvLimits()
            val collector = ImportedXmlTvCollector("source-a", listOf(channel(key = ownedKey)), now,
                currentCoroutineContext(), { true }, limits)
            val xml = "<tv>" + programme(key = xmlKey, title = "Literal &amp;amp;",
                from = "&#50;0260101120000 +0000") + "</tv>"
            val parsed = parseImportedXmlTvStream(reader(xml, 1), collector, limits)
            val row = parsed.programmes.getValue("source-a:1").single()
            assertEquals("Literal &amp;", row.title)
            assertEquals(now, row.startEpochMs)
        }
    }

    @Test
    fun `forbidden general parameter external and undeclared entities cannot become owned guide attributes`() = runTest {
        val documents = listOf(
            "<!DOCTYPE tv [<!ENTITY owned 'bbc.uk'>]><tv>" + programme(key = "&owned;") + "</tv>",
            "<!DOCTYPE tv [<!ENTITY owned SYSTEM 'file:///etc/passwd'>]><tv>" + programme(key = "&owned;") + "</tv>",
            "<!DOCTYPE tv [<!ENTITY % remote SYSTEM 'https://never-fetch.example.invalid/evil.dtd'>%remote;]><tv/>",
            "<!DOCTYPE tv SYSTEM 'https://never-fetch.example.invalid/guide.dtd'><tv>" + programme(key = "&undeclared;") + "</tv>",
        )
        for (xml in documents) {
            val limits = ImportedXmlTvLimits()
            val collector = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { true }, limits)
            assertFailsWith<Exception> { parseImportedXmlTvStream(reader(xml, 1), collector, limits) }
        }
    }

    @Test
    fun `global feed is scanned past one hundred thousand unrelated programmes`() = runTest {
        val limits = ImportedXmlTvLimits(retainedProgrammes = 1)
        val collector = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { true }, limits)
        val unrelated = programme(key = "unrelated").encodeToByteArray()
        val matching = programme().encodeToByteArray()
        var piece = "<tv>".encodeToByteArray()
        var offset = 0
        var index = 0
        val read: suspend (ByteArray) -> Int = { output ->
            var written = 0
            while (written < output.size) {
                if (offset == piece.size) {
                    piece = when (index++) {
                        in 0 until 100_001 -> unrelated
                        100_001 -> matching
                        100_002 -> "</tv>".encodeToByteArray()
                        else -> byteArrayOf()
                    }
                    offset = 0
                }
                if (piece.isEmpty()) break
                val count = minOf(output.size - written, piece.size - offset)
                piece.copyInto(output, written, offset, offset + count); offset += count; written += count
            }
            if (written == 0) -1 else written
        }
        val result = parseImportedXmlTvStream(read, collector, limits)
        assertEquals("News", result.programmes.getValue("source-a:1").single().title)
    }

    @Test
    fun `only owned provider keys are retained exact spelling wins and aliases consume the global budget`() = runTest {
        val limits = ImportedXmlTvLimits(retainedProgrammes = 2)
        val channels = listOf(channel(), channel(id = "2"), channel("source-b", "3"), channel(id = "4", key = "other"))
        val collector = ImportedXmlTvCollector("source-a", channels, now, currentCoroutineContext(), { true }, limits)
        val xml = "<tv>" + programme(key = "BBC.UK", title = "Wrong casing") + programme() + programme(key = "other") + "</tv>"
        val result = parseImportedXmlTvStream(reader(xml), collector, limits)
        assertEquals(setOf("source-a:1", "source-a:2"), result.programmes.keys)
        assertEquals(2, result.programmes.values.sumOf { it.size })
        assertTrue(result.programmes.all { (id, rows) -> rows.all { it.channelId == id && it.title == "News" } })
        assertTrue(result.wasTruncated)
    }

    @Test
    fun `expired invalid and distant rows do not displace nearest valid rows in reverse ordered feed`() = runTest {
        val limits = ImportedXmlTvLimits(perChannel = 2)
        val collector = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { true }, limits)
        val xml = "<tv>" +
            programme(title = "Later", from = "20260101160000 +0000", stop = "20260101170000 +0000") +
            programme(title = "Next", from = "20260101130000 +0000", stop = "20260101140000 +0000") + programme() +
            programme(title = "Expired", from = "20260101100000 +0000", stop = "20260101110000 +0000") +
            programme(title = "Wrong interval", from = "20260101140000 +0000", stop = "20260101130000 +0000") +
            programme(title = "Distant", from = "20260104120000 +0000", stop = "20260104130000 +0000") + "</tv>"
        val result = parseImportedXmlTvStream(reader(xml), collector, limits)
        assertEquals(listOf("News", "Next"), result.programmes.getValue("source-a:1").map { it.title })
    }

    @Test
    fun `title and metadata have finite retention and DTD entities cannot expand`() = runTest {
        val limits = ImportedXmlTvLimits()
        val collector = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { true }, limits)
        val result = parseImportedXmlTvStream(reader("<tv>" + programme(title = "N".repeat(4_000), description = "D".repeat(8_000)) + "</tv>"), collector, limits)
        assertEquals(512, result.programmes.getValue("source-a:1").single().title.length)
        assertEquals(2048, result.programmes.getValue("source-a:1").single().description?.length)
        val other = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { true }, limits)
        assertFailsWith<IllegalStateException> {
            parseImportedXmlTvStream(reader("<!DOCTYPE tv [<!ENTITY leak SYSTEM \"file:///etc/passwd\">]><tv>" + programme(title = "&leak;") + "</tv>", 1), other, limits)
        }
    }

    @Test
    fun `decoded budget is enforced while streaming before publishing and source changes cancel publication`() = runTest {
        val limits = ImportedXmlTvLimits(decodedBytes = 64)
        val collector = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { true }, limits)
        assertFailsWith<IllegalStateException> { parseImportedXmlTvStream(reader("<tv>" + programme() + "</tv>"), collector, limits) }
        val otherLimits = ImportedXmlTvLimits()
        var current = true
        val other = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { current }, otherLimits)
        val input = reader("<tv>" + programme() + "</tv>")
        val changing: suspend (ByteArray) -> Int = { output -> input(output).also { current = false } }
        assertFailsWith<IllegalStateException> { parseImportedXmlTvStream(changing, other, otherLimits) }
        val cancelled = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { throw CancellationException("test cancellation") }, otherLimits)
        assertFailsWith<CancellationException> { parseImportedXmlTvStream(reader("<tv/>"), cancelled, otherLimits) }
    }

    @Test
    fun `guard bounds unfinished tokens invalid UTF8 and split declarations before native parser allocation`() {
        val limits = ImportedXmlTvLimits(tokenBytes = 256)
        val guard = ImportedXmlTvByteGuard(limits)
        "<tv><desc>".encodeToByteArray().let { guard.accept(it, it.size) }
        assertFailsWith<IllegalStateException> { ByteArray(257) { 65 }.let { guard.accept(it, it.size) } }
        val utf8 = ImportedXmlTvByteGuard(limits)
        utf8.accept(byteArrayOf(0xc3.toByte()), 1)
        assertFailsWith<IllegalStateException> { utf8.accept(byteArrayOf(0x28), 1) }
        val declaration = ImportedXmlTvByteGuard(limits)
        "<!DOCTYPE tv ".encodeToByteArray().forEach { byte -> declaration.accept(byteArrayOf(byte), 1) }
        assertFailsWith<IllegalStateException> { declaration.accept(byteArrayOf('['.code.toByte()), 1) }
    }

    @Test
    fun `transport and decoded counters are independent and reject before count overflow`() {
        val delivered = ImportedXmlTvByteBudget(3)
        val decoded = ImportedXmlTvByteBudget(10)
        delivered.accept(3)
        decoded.accept(10)
        assertFailsWith<IllegalStateException> { delivered.accept(1) }
        assertFailsWith<IllegalStateException> { decoded.accept(Int.MAX_VALUE) }
        val wide = ImportedXmlTvByteBudget(Long.MAX_VALUE)
        wide.accept(Int.MAX_VALUE)
        wide.accept(Int.MAX_VALUE)
    }

    @Test
    fun `native XML well formedness and guide root checks prevent partial publication`() = runTest {
        val limits = ImportedXmlTvLimits()
        for (xml in listOf("<tv>" + programme(), "<tv><title>broken</desc></tv>",
            "<html>" + programme() + "</html>", "<tv>" + programme(title = "&undeclared;") + "</tv>")) {
            val collector = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { true }, limits)
            assertFailsWith<Exception> { parseImportedXmlTvStream(reader(xml, 1), collector, limits) }
        }
    }

    @Test
    fun `parent cancellation interrupts an awaiting stream read without publishing rows`() = runTest {
        val enteredRead = CompletableDeferred<Unit>()
        val limits = ImportedXmlTvLimits()
        var published = false
        val pending = async {
            val collector = ImportedXmlTvCollector("source-a", listOf(channel()), now, currentCoroutineContext(), { true }, limits)
            parseImportedXmlTvStream(read = {
                enteredRead.complete(Unit)
                awaitCancellation()
            }, collector, limits)
            published = true
        }
        enteredRead.await()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertFalse(published)
    }
}
