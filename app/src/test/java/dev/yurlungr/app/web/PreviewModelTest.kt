package dev.yurlungr.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewModelTest {
    @Test fun phoneWidthFillsTheScreen() {
        assertEquals(PreviewFrame(1080, 2000, 1f), PreviewFrame.fit(1080, 2000, 2.75f, null))
    }

    @Test fun widerLayoutsAreScaledToFit() {
        // 1080 px at density 2.75 is ~393 CSS px; a 1280 px desktop renders at 3520 px and scales down.
        val desktop = PreviewFrame.fit(1080, 2000, 2.75f, 1280)
        assertEquals(3520, desktop.width)
        assertEquals(1080f / 3520, desktop.scale, 0.0001f)
        assertEquals(2000f, desktop.height * desktop.scale, 1f)
        // A screen already that wide needs no scaling.
        assertEquals(PreviewFrame(2560, 1600, 1f), PreviewFrame.fit(2560, 1600, 2f, 768))
    }

    @Test fun userAgentsPerWidth() {
        val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0 Mobile Safari/537.36"
        assertNull(PreviewWidth.Phone.userAgent(ua))
        val tablet = PreviewWidth.Tablet.userAgent(ua)!!
        assertTrue("Android 14" in tablet && " Mobile" !in tablet)
        val desktop = PreviewWidth.Desktop.userAgent(ua)!!
        assertTrue("X11; Linux x86_64;" in desktop && "Android" !in desktop && " Mobile" !in desktop)
    }

    @Test fun failedRequestsAndCopyText() {
        val missing = PreviewLogs.request("GET", "http://127.0.0.1:5173/api/items?x=1", 404, "Not Found")
        assertEquals(PreviewLogLevel.Error, missing.level)
        assertEquals("GET http://127.0.0.1:5173/api/items?x=1 → 404 Not Found", missing.text)
        assertEquals(PreviewLogLevel.Warning, PreviewLogs.request("GET", "http://127.0.0.1:5173/favicon.ico", 404, "").level)
        assertEquals("POST http://127.0.0.1:5173/a → net::ERR_CONNECTION_REFUSED", PreviewLogs.request("POST", "http://127.0.0.1:5173/a", null, "net::ERR_CONNECTION_REFUSED").text)
        val text = PreviewLogs.format(listOf(PreviewLogEntry(PreviewLogLevel.Warning, "deprecated", "main.js:3"), missing))
        assertEquals("[warning] deprecated (main.js:3)\n[error] GET http://127.0.0.1:5173/api/items?x=1 → 404 Not Found", text)
    }

    @Test fun encodesQrCodes() {
        val qr = QrModules.encode("https://quiet-river-1234.trycloudflare.com")
        assertTrue(qr.size >= 21 && (qr.size - 21) % 4 == 0)
        // Finder patterns: dark corners, with a light ring inside.
        assertTrue(qr[0, 0] && qr[qr.size - 1, 0] && qr[0, qr.size - 1])
        assertTrue(!qr[1, 1] && qr[3, 3])
    }
}
