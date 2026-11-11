package dev.yurlungr.app.projects

import dev.yurlungr.app.agents.Agent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffAndFilterTest {
    private val diff = """
        diff --git a/app.kt b/app.kt
        index 1111111..2222222 100644
        --- a/app.kt
        +++ b/app.kt
        @@ -10,4 +10,5 @@ fun main() {
         val a = 1
        -val b = 2
        +val b = 3
        +-- not a header, an added line
         val c = 4
        --- not a header either, a removed line
    """.trimIndent()

    @Test fun `hunks carry old and new line numbers`() {
        val p = ParsedDiff.parse(diff)
        val kinds = p.lines.map { it.kind }
        assertEquals(DiffLine.Kind.Hunk, kinds.first())
        assertEquals("fun main() {", p.lines.first().text)
        val context = p.lines[1]
        assertEquals(10, context.oldNo); assertEquals(10, context.newNo)
        val removed = p.lines.first { it.kind == DiffLine.Kind.Removed }
        assertEquals("val b = 2", removed.text); assertEquals(11, removed.oldNo); assertNull(removed.newNo)
        val added = p.lines.filter { it.kind == DiffLine.Kind.Added }
        assertEquals(listOf(11, 12), added.map { it.newNo })
        assertEquals(2, p.added)
        assertEquals(2, p.removed)
    }

    @Test fun `lines starting with -- or ++ inside a hunk are content, not headers`() {
        val p = ParsedDiff.parse(diff)
        assertTrue(p.lines.any { it.kind == DiffLine.Kind.Added && it.text == "-- not a header, an added line" })
        assertTrue(p.lines.any { it.kind == DiffLine.Kind.Removed && it.text == "-- not a header either, a removed line" })
    }

    @Test fun `new files, renames and binaries are noted`() {
        val newFile = ParsedDiff.parse("@@ new file: x.txt @@\n+one\n+two\n")
        assertEquals(listOf(1, 2), newFile.lines.filter { it.kind == DiffLine.Kind.Added }.map { it.newNo })
        val meta = ParsedDiff.parse("diff --git a/a b/b\nsimilarity index 90%\nrename from a\nrename to b\nBinary files a/a and b/b differ\n")
        assertTrue(meta.notes.contains("Renamed from a"))
        assertTrue(meta.notes.any { it.startsWith("Binary") })
        assertTrue(meta.isEmpty)
    }

    @Test fun `changed words are found between a removed and an added line`() {
        val (a, b) = ParsedDiff.changedSpan("val total = price * 2", "val total = price * 3")!!
        assertEquals("2", "val total = price * 2".substring(a.first, a.last + 1))
        assertEquals("3", "val total = price * 3".substring(b.first, b.last + 1))
        assertNull("unrelated lines aren't highlighted", ParsedDiff.changedSpan("abc", "xyz"))
    }

    @Test fun `history filters become fixed-string git arguments`() {
        assertEquals(emptyList<String>(), HistoryFilter().gitArgs())
        assertFalse(HistoryFilter().isActive)
        val f = HistoryFilter(author = "Ada (work)", period = HistoryPeriod.Week, query = "fix(ui)")
        assertTrue(f.isActive)
        assertEquals(
            listOf("--fixed-strings", "--regexp-ignore-case", "--author=Ada (work)", "--since=7 days ago", "--grep=fix(ui)"),
            f.gitArgs(),
        )
    }

    @Test fun `hash-like queries search hashes, ordinary words search messages`() {
        assertEquals("9821617", HistoryFilter(query = "9821617").hashQuery)
        assertEquals("a1b2", HistoryFilter(query = "A1B2").hashQuery)
        assertNull("a hex-looking word is still a word", HistoryFilter(query = "cafe").hashQuery)
        assertNull(HistoryFilter(query = "add").hashQuery)
        assertFalse("hash queries don't grep messages", HistoryFilter(query = "9821617").gitArgs().any { it.startsWith("--grep") })
        val c = GitCommit("9821617abcdef0123456789abcdef0123456789", emptyList(), "me", 0, emptyList(), "subject")
        assertTrue(HistoryFilter(query = "9821617").matchesHash(c))
        assertFalse(HistoryFilter(query = "1234567").matchesHash(c))
    }

    @Test fun `optional agents install on their own with npm, core ones don't`() {
        val optional = Agent.entries.filter { it.isOptional }
        assertEquals(setOf(Agent.Qwen, Agent.Kimi, Agent.Auggie, Agent.CommandCode, Agent.MiniMax, Agent.Pi, Agent.Qoder,
            Agent.Copilot, Agent.Goose, Agent.OpenCode, Agent.Cline, Agent.Kilo, Agent.Cursor, Agent.Amp, Agent.MistralVibe, Agent.Kiro,
            Agent.FastAgent, Agent.MinionCode), optional.toSet())
        for (a in optional.filter { it.npmPackage != null }) {
            val cmd = a.installCommand()!!
            assertTrue(cmd.contains("npm install -g"))
            for (pkg in a.npmPackage!!.split(' ')) assertTrue(cmd, cmd.contains("'$pkg@latest'"))
            assertTrue("checks npm is there first", cmd.startsWith("command -v npm"))
        }
        // Pi's adapter and the CLI it drives install together.
        assertTrue(Agent.Pi.installCommand()!!.contains("'pi-acp@latest' '@mariozechner/pi-coding-agent@latest'"))
        assertNull(Agent.Claude.installCommand())
        // Native and Python ones go through the agent-install.sh recipe, parsed before it runs.
        for (a in optional.filter { it.installRecipe != null }) {
            val cmd = a.installCommand()!!
            assertTrue(cmd.startsWith("bash -n ") && cmd.endsWith("agent-install.sh\" ${a.installRecipe}"))
        }
        assertEquals("copilot --acp", Agent.Copilot.launchCommand(false))
        assertEquals("goose acp", Agent.Goose.launchCommand(false))
        assertEquals("opencode acp", Agent.OpenCode.launchCommand(false))
        assertEquals("qwen --acp", Agent.Qwen.launchCommand(false))
        assertEquals("kimi acp", Agent.Kimi.launchCommand(false))
        assertEquals("auggie --acp", Agent.Auggie.launchCommand(false))
        assertEquals("not Android's /system/bin/cmd", "commandcode acp", Agent.CommandCode.launchCommand(false))
        assertEquals("cline --acp", Agent.Cline.launchCommand(false))
        assertEquals("kiro-cli acp --agent-engine=v3", Agent.Kiro.launchCommand(false))
        assertEquals("not Minion's mcode", "mcode acp", Agent.MiniMax.launchCommand(false))
        assertEquals("minion-code acp", Agent.MinionCode.launchCommand(false))
        // Key-based agents set up through agent-key.sh, with a recipe it knows.
        for (a in listOf(Agent.MistralVibe, Agent.FastAgent, Agent.MinionCode)) {
            assertTrue(a.loginCommand, a.loginCommand.startsWith("bash \"\$PREFIX/libexec/yurlungr/agent-key.sh\" "))
        }
        assertEquals(dev.yurlungr.app.runtime.AGENT_RECIPES, optional.mapNotNull { it.installRecipe }.toSet())
        // The installer accepts exactly those recipes.
        val accepted = java.io.File("src/main/assets/agent-install.sh").readLines()
            .first { it.trimStart().startsWith("copilot|") }.substringBefore(')').trim().split('|').toSet()
        assertEquals(dev.yurlungr.app.runtime.AGENT_RECIPES, accepted)
    }
}
