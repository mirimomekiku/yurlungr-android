package dev.yurlungr.app.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Checkpoint history parsing, capped attachment reads and concurrent atomic writes. */
class AuditFixesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `one malformed turn doesn't lose the others`() {
        val turns = Checkpoints.parseTurns(
            """[{"index":1,"prompt":"a","startedAt":5},{"prompt":"no index"},"junk",{"index":{"x":1}},{"index":3,"prompt":"c","restore":true}]""",
        )!!
        assertEquals(listOf(1, 3), turns.map { it.index })
        assertTrue(turns[1].restore)
        assertNull(Checkpoints.parseTurns("{not json"))
        assertNull(Checkpoints.parseTurns("""{"index":1}"""))
    }

    @Test
    fun `an unreadable turns file is moved aside, not overwritten`() {
        val f = File(tmp.root, "c.json").apply { writeText("{broken") }
        assertEquals(emptyList<ChatTurn>(), Checkpoints.turnsForUpdate(f))
        assertEquals("{broken", File(tmp.root, "c.json.bad").readText())
        assertTrue(!f.exists())
        // A readable one is returned as is.
        f.writeText("""[{"index":1,"prompt":"a","startedAt":1}]""")
        assertEquals(1, Checkpoints.turnsForUpdate(f).size)
        assertTrue(f.exists())
    }

    @Test
    fun `capped read stops past the limit`() {
        val data = ByteArray(200_000) { it.toByte() }
        assertArrayEquals(data, Attachments.readCapped(ByteArrayInputStream(data), 200_000))
        assertNull(Attachments.readCapped(ByteArrayInputStream(data), 199_999))
        assertEquals(0, Attachments.readCapped(ByteArrayInputStream(ByteArray(0)), 10)!!.size)
    }

    @Test
    fun `concurrent atomic writes to one file don't clash`() {
        val dir = tmp.newFolder("w")
        val target = File(dir, "a.txt")
        val pool = Executors.newFixedThreadPool(8)
        val texts = (0 until 40).map { "text $it ".repeat(2000) }
        val failures = texts.map { t -> pool.submit<Throwable?> { runCatching { ChatSession.writeAtomically(target, t) }.exceptionOrNull() } }
            .mapNotNull { it.get() }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        assertEquals(emptyList<Throwable>(), failures)
        assertEquals(listOf("a.txt"), dir.list()!!.toList())
    }
}
