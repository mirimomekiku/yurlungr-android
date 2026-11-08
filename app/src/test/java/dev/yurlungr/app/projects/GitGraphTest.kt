package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class GitGraphTest {
    private fun c(h: String, vararg parents: String) = GitCommit(h.padEnd(40, '0'), parents.map { it.padEnd(40, '0') }, "a", 0, emptyList(), h)

    @Test
    fun `linear history stays in lane 0`() {
        val rows = GitGraph.layout(listOf(c("c", "b"), c("b", "a"), c("a")))
        assertTrue(rows.all { it.lane == 0 && it.width == 1 })
        assertEquals(listOf(0), rows[0].outgoing)
        assertEquals(emptyList<Int>(), rows[2].outgoing) // root
    }

    @Test
    fun `merge fans out and the branch rejoins`() {
        // m merges f into main: m -> (b, f); f -> a; b -> a
        val rows = GitGraph.layout(listOf(c("m", "b", "f"), c("f", "a"), c("b", "a"), c("a")))
        assertEquals(0, rows[0].lane)
        assertEquals(listOf(0, 1), rows[0].outgoing)
        assertEquals(1, rows[1].lane) // feature commit in its own lane
        assertEquals(listOf(0), rows[1].through) // main passes by
        assertEquals(0, rows[2].lane)
        assertEquals(0, rows[3].lane)
        assertEquals(listOf(1 to 0), rows[2].shifts) // the feature line slides back into main
        assertEquals(listOf(0), rows[3].incoming)
    }

    @Test
    fun `parses log records`() {
        val h1 = "a".repeat(40)
        val h2 = "b".repeat(40)
        val out = "$h1\u001f$h2\u001fAda\u001f1700000000\u001fHEAD -> main, origin/main, tag: v1\u001fFix build\u001e\n" +
            "$h2\u001f\u001fAda\u001f1690000000\u001f\u001fInitial\u001e"
        val log = Git.parseLog(out)
        assertEquals(2, log.size)
        assertEquals(listOf(h2), log[0].parents)
        assertEquals(listOf("HEAD -> main", "origin/main", "tag: v1"), log[0].refs)
        assertEquals(1_700_000_000_000L, log[0].time)
        assertTrue(log[1].parents.isEmpty())
    }

    @Test
    fun `day labels`() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 8, 17, 0) }.timeInMillis
        val hour = 3_600_000L
        assertEquals("Today", dayLabel(now - 2 * hour, now))
        assertEquals("Yesterday", dayLabel(now - 25 * hour, now)) // was "25 hours ago"
        assertTrue(dayLabel(now - 3 * 24 * hour, now).isNotEmpty())
    }
}
