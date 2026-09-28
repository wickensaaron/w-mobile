@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.xmlinterop.wm_gzip_create
import com.nuvio.app.features.livetv.xmlinterop.wm_gzip_destroy
import com.nuvio.app.features.livetv.xmlinterop.wm_gzip_feed
import com.nuvio.app.features.livetv.xmlinterop.wm_gzip_finish
import com.nuvio.app.features.livetv.xmlinterop.wm_xml_create
import com.nuvio.app.features.livetv.xmlinterop.wm_xml_destroy
import com.nuvio.app.features.livetv.xmlinterop.wm_xml_feed
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private class NativeImportedXmlTvState(val collector: ImportedXmlTvCollector, val guard: ImportedXmlTvByteGuard) {
    var parser: COpaquePointer? = null
    var failure: Throwable? = null

    // A Kotlin exception must never unwind through a C SAX/zlib callback.
    fun event(block: () -> Unit): Int {
        if (failure != null) return 0
        return try { collector.checkCurrent(force = false); block(); 1 } catch (error: Throwable) {
            if (failure == null) failure = error
            0
        }
    }

    fun decoded(bytes: CPointer<UByteVar>?, length: Int): Int = event {
        check(bytes != null && length in 1..16 * 1024) { "Invalid Live TV decoder output" }
        guard.accept(bytes.readBytes(length), length)
        check(wm_xml_feed(parser, bytes, length, 0) != 0) { "Invalid XMLTV guide" }
        failure?.let { throw it }
    }
}

private val importedXmlTvStart = staticCFunction {
    opaque: COpaquePointer?, name: CPointer<ByteVar>?, channel: CPointer<ByteVar>?, start: CPointer<ByteVar>?, stop: CPointer<ByteVar>? ->
    val state = opaque!!.asStableRef<NativeImportedXmlTvState>().get()
    state.event { state.collector.startElement(name?.toKString().orEmpty(), channel?.toKString(), start?.toKString(), stop?.toKString()) }
}
private val importedXmlTvEnd = staticCFunction { opaque: COpaquePointer?, name: CPointer<ByteVar>? ->
    val state = opaque!!.asStableRef<NativeImportedXmlTvState>().get()
    state.event { state.collector.endElement(name?.toKString().orEmpty()) }
}
private val importedXmlTvText = staticCFunction { opaque: COpaquePointer?, bytes: CPointer<ByteVar>?, length: Int ->
    val state = opaque!!.asStableRef<NativeImportedXmlTvState>().get()
    state.event {
        check(bytes != null && length in 0..65_536) { "Live TV XML text exceeds device limit" }
        if (state.collector.needsText) state.collector.text(bytes.readBytes(length).decodeToString(throwOnInvalidSequence = true))
    }
}
private val importedXmlTvGzipOutput = staticCFunction { opaque: COpaquePointer?, bytes: CPointer<UByteVar>?, length: Int ->
    opaque!!.asStableRef<NativeImportedXmlTvState>().get().decoded(bytes, length)
}

internal actual suspend fun parseImportedXmlTvStream(
    read: suspend (ByteArray) -> Int,
    collector: ImportedXmlTvCollector,
    limits: ImportedXmlTvLimits,
): ImportedXmlTvResult = withContext(Dispatchers.Default) {
    val state = NativeImportedXmlTvState(collector, ImportedXmlTvByteGuard(limits))
    val reference = StableRef.create(state)
    var gzip: COpaquePointer? = null
    try {
        state.parser = wm_xml_create(reference.asCPointer(), importedXmlTvStart, importedXmlTvEnd, importedXmlTvText)
        check(state.parser != null) { "XMLTV reader unavailable" }
        val buffer = ByteArray(16 * 1024)
        val prefix = ByteArray(2)
        var prefixSize = 0
        var probed = false
        var compressed = false
        fun consume(bytes: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            collector.checkCurrent()
            bytes.usePinned { pinned ->
                val address = pinned.addressOf(offset).reinterpret<UByteVar>()
                val accepted = if (compressed) wm_gzip_feed(gzip, address, length) else state.decoded(address, length)
                state.failure?.let { throw it }
                check(accepted != 0) { "Invalid XMLTV guide or compression" }
            }
        }
        while (true) {
            collector.checkCurrent()
            val size = read(buffer)
            if (size < 0) break
            if (size == 0) { yield(); continue }
            var offset = 0
            if (!probed) {
                while (prefixSize < 2 && offset < size) prefix[prefixSize++] = buffer[offset++]
                if (prefixSize < 2) continue
                compressed = prefix[0] == 0x1f.toByte() && prefix[1] == 0x8b.toByte()
                if (compressed) {
                    gzip = wm_gzip_create(reference.asCPointer(), importedXmlTvGzipOutput)
                    check(gzip != null) { "Live TV guide decoder unavailable" }
                }
                probed = true
                consume(prefix, 0, prefixSize)
            }
            consume(buffer, offset, size - offset)
            yield()
        }
        if (!probed && prefixSize != 0) consume(prefix, 0, prefixSize)
        collector.checkCurrent()
        if (compressed) check(wm_gzip_finish(gzip) != 0) { "Incomplete compressed XMLTV guide" }
        state.guard.finish()
        val finished = wm_xml_feed(state.parser, null, 0, 1)
        state.failure?.let { throw it }
        check(finished != 0) { "Incomplete XMLTV guide" }
        collector.snapshot()
    } finally {
        wm_gzip_destroy(gzip)
        wm_xml_destroy(state.parser)
        reference.dispose()
    }
}
