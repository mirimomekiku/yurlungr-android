package dev.yurlungr.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class RichBundleTest {
    @Test fun `the manifest pins every file with a size and a SHA-256`() {
        val files = RichBundle.files
        assertEquals(23, files.size)
        assertEquals(files.size, files.map { it.path }.toSet().size)
        assertTrue(files.all { it.size > 0 && it.sha256.length == 64 && it.sha256.all { c -> c in '0'..'9' || c in 'a'..'f' } })
        assertEquals(20, files.count { it.path.startsWith("fonts/KaTeX_") && it.path.endsWith(".woff2") })
        assertEquals(3_895_301L, RichBundle.totalBytes)
        assertEquals("3.7 MB", RichBundle.sizeLabel())
    }

    @Test fun `downloads come from exact versions on jsDelivr over https`() {
        assertEquals("https://cdn.jsdelivr.net/npm/mermaid@10.9.3/dist/mermaid.min.js", RichBundle.urlFor("mermaid.min.js"))
        assertEquals("https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css", RichBundle.urlFor("katex.min.css"))
        assertEquals(
            "https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/fonts/KaTeX_Main-Regular.woff2",
            RichBundle.urlFor("fonts/KaTeX_Main-Regular.woff2"),
        )
        assertTrue(RichBundle.files.all { it.url.startsWith("https://cdn.jsdelivr.net/npm/") && "@" in it.url })
    }

    @Test fun `only listed files are served, whatever the request looks like`() {
        val base = RichBundle.BASE_URL
        assertEquals("katex.min.js", RichBundle.pathFor(base + "katex.min.js")?.path)
        assertEquals("fonts/KaTeX_AMS-Regular.woff2", RichBundle.pathFor(base + "fonts/KaTeX_AMS-Regular.woff2?v=1#x")?.path)
        assertNull(RichBundle.pathFor(base + "fonts/KaTeX_AMS-Regular.woff"))
        assertNull(RichBundle.pathFor(base + "../shared_prefs/x.xml"))
        assertNull(RichBundle.pathFor(base + ".complete"))
        assertNull(RichBundle.pathFor("https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"))
        assertNull(RichBundle.pathFor("file:///android_asset/richtext/katex.min.js"))
        assertEquals("text/css", RichBundle.mimeType("katex.min.css"))
        assertEquals("font/woff2", RichBundle.mimeType("fonts/KaTeX_AMS-Regular.woff2"))
    }

    @Test fun `bytes must match size and hash exactly`() {
        val bytes = "hello".toByteArray()
        val sha = RichBundle.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha)
        val file = RichFile("x.js", bytes.size.toLong(), sha)
        assertTrue(RichBundle.matches(file, 5, sha.uppercase()))
        assertFalse(RichBundle.matches(file, 6, sha))
        assertFalse(RichBundle.matches(file, 5, "0".repeat(64)))
    }

    @Test fun `older versions and leftovers are cleaned up, nothing else`() {
        val names = listOf(RichBundle.DIR, RichBundle.DIR + ".part", "richtext-mermaid-10.0.0-katex-0.16.0", "chats", "richtext")
        assertEquals(listOf(RichBundle.DIR + ".part", "richtext-mermaid-10.0.0-katex-0.16.0"), RichBundle.staleDirs(names))
    }
}
