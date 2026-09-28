package com.nuvio.app.features.livetv

import java.io.InputStream
import java.io.PushbackInputStream
import java.io.StringReader
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

internal actual suspend fun parseImportedXmlTvStream(
    read: suspend (ByteArray) -> Int,
    collector: ImportedXmlTvCollector,
    limits: ImportedXmlTvLimits,
): ImportedXmlTvResult = withContext(Dispatchers.IO) {
    val context = currentCoroutineContext()
    // One 16 KiB bridge; SAX blocks only this IO worker while the HTTP channel suspends.
    val raw = object : InputStream() {
        private val buffer = ByteArray(16 * 1024)
        private var offset = 0
        private var length = 0
        private fun availableChunk(): Boolean {
            collector.checkCurrent()
            if (offset < length) return true
            do { length = runBlocking(context) { read(buffer) } } while (length == 0)
            offset = 0
            return length > 0
        }
        override fun read(): Int = if (availableChunk()) buffer[offset++].toInt() and 255 else -1
        override fun read(bytes: ByteArray, start: Int, size: Int): Int {
            if (size == 0) return 0
            if (!availableChunk()) return -1
            val count = minOf(size, length - offset)
            buffer.copyInto(bytes, start, offset, offset + count)
            offset += count
            return count
        }
    }
    val probe = PushbackInputStream(raw, 2)
    val first = probe.read()
    val second = probe.read()
    if (second >= 0) probe.unread(second)
    if (first >= 0) probe.unread(first)
    // Some engines transparently decode Content-Encoding; sniff actual delivered bytes, not headers.
    val decoded = if (first == 0x1f && second == 0x8b) GZIPInputStream(probe, 16 * 1024) else probe
    val guard = ImportedXmlTvByteGuard(limits)
    val safe = object : InputStream() {
        private val single = ByteArray(1)
        override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
        override fun read(bytes: ByteArray, start: Int, size: Int): Int {
            collector.checkCurrent()
            val count = decoded.read(bytes, start, minOf(size, 16 * 1024))
            if (count > 0) {
                // SAX normally requests offset 0. A bounded copy covers any other offset.
                if (start == 0) guard.accept(bytes, count)
                else guard.accept(bytes.copyOfRange(start, start + count), count)
            }
            return count
        }
    }
    val factory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = false
        // Android implementations differ in supported flags. The guard rejects all DTD subsets,
        // and the resolver below independently prevents external DTD/entity/file/network loads.
        runCatching { setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    }
    decoded.use {
        factory.newSAXParser().parse(safe, object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                collector.startElement(qName.orEmpty(), attributes.getValue("channel"), attributes.getValue("start"), attributes.getValue("stop"))
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) { collector.endElement(qName.orEmpty()) }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                collector.checkCurrent(force = false)
                if (collector.needsText) collector.text(String(ch, start, length))
            }
            override fun error(error: SAXParseException) { throw IllegalStateException("Invalid XMLTV guide") }
            override fun fatalError(error: SAXParseException) { throw IllegalStateException("Invalid XMLTV guide") }
            override fun skippedEntity(name: String?) { throw IllegalStateException("Unsupported XMLTV entity") }
        })
        // GZIPInputStream validates gzip CRC/trailer when reading EOF. Complete XML still needs EOF.
        while (safe.read() >= 0) collector.checkCurrent()
        guard.finish()
    }
    collector.snapshot()
}
