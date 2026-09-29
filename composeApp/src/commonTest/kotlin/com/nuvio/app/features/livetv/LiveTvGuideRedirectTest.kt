package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LiveTvGuideRedirectTest {
    private val guide = "https://tv.example.test/xmltv.php?username=u&password=secret"

    @Test
    fun `relative redirect uses provider host without copying guide credentials`() {
        assertEquals("https://tv.example.test/guide.xml.gz", resolveImportedGuideRedirect(guide, "/guide.xml.gz"))
        assertEquals("https://tv.example.test/next.xml", resolveImportedGuideRedirect(guide, "next.xml"))
        assertEquals("https://tv.example.test/xmltv.php?ticket=one", resolveImportedGuideRedirect(guide, "?ticket=one"))
    }

    @Test
    fun `secure cross-host redirect follows the provider location only`() {
        assertEquals("https://cdn.example.test/guide.xml.gz",
            resolveImportedGuideRedirect(guide, "https://cdn.example.test/guide.xml.gz"))
    }

    @Test
    fun `redirect cannot downgrade or introduce url user credentials`() {
        assertFailsWith<IllegalArgumentException> { resolveImportedGuideRedirect(guide, "http://tv.example.test/guide.xml") }
        assertFailsWith<IllegalArgumentException> { resolveImportedGuideRedirect(guide, "https://user:pass@cdn.example.test/guide.xml") }
        assertFailsWith<IllegalArgumentException> { resolveImportedGuideRedirect(guide, "https://cdn.example.test/guide.xml#fragment") }
    }
}
