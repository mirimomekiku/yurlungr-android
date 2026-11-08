package dev.yurlungr.app.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimeStatusTest {
    @Test fun `session summaries retain stable IDs after numbering changes`() {
        val before = formatRuntimeStatus(listOf(MotdSession("first", 1, "shell"), MotdSession("second", 2, "Project")), 1, 2, 1)
        assertTrue(before.contains("session\tsecond\t2\t2\tProject\n"))
        val after = formatRuntimeStatus(listOf(MotdSession("second", 1, "Project")), 0, 2, 0)
        assertTrue(after.contains("session\tsecond\t1\t1\tProject\n"))
        assertTrue(after.endsWith("agents\t0\t2\t0\n"))
    }

    @Test fun `session names cannot inject terminal escapes or status records`() {
        val text = formatRuntimeStatus(listOf(MotdSession("id", 1, "safe\t\n\r\u001b[2J\u009b31m")), 0, 0, 0)
        assertEquals(2, text.lineSequence().count { it.isNotEmpty() })
        assertTrue(text.contains("safe[2J31m"))
        assertFalse(text.contains('\u001b'))
        assertFalse(text.contains('\u009b'))
    }

    @Test fun `empty registry is an honest empty summary`() {
        assertEquals("agents\t0\t0\t0\n", formatRuntimeStatus(emptyList(), 0, 0, 0))
    }
}
