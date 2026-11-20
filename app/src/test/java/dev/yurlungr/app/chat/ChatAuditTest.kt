package dev.yurlungr.app.chat

import dev.yurlungr.app.acp.ProcessGroup
import dev.yurlungr.app.projects.McpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Fixes and features from the chat audit: approvals, file modes, terminals, MCP, streaming, storage. */
class ChatAuditTest {
    @get:Rule val tmp = TemporaryFolder()

    private val data by lazy { tmp.newFolder("data") }
    private val files by lazy { File(data, "files").apply { mkdirs() } }
    private val home by lazy { File(files, "home").apply { mkdirs() } }
    private val project by lazy { File(home, "projects/app").apply { mkdirs() } }
    private val policy by lazy { PathPolicy(data, files, home) }

    // ---- approval rules -------------------------------------------------------------------

    @Test
    fun `agent and tool config is never auto-approved`() {
        val p = project.path
        val rules = listOf(ApprovalRule(p, "edit"), ApprovalRule(p, "read"))
        for (rel in listOf(
            ".claude/settings.json", ".claude/commands/x.md", ".mcp.json", ".gemini/settings.json", ".codex/config.toml",
            ".qwen/settings.json", ".vscode/tasks.json", ".envrc", ".env", ".env.local", ".git/hooks/pre-commit",
            ".husky/pre-commit", ".githooks/post-checkout", "packages/web/.env.production", "packages/web/.claude/settings.local.json",
        )) {
            assertFalse(rel, ApprovalRules.allows(rules, p, "edit", listOf(File(project, rel).path), policy))
        }
        for (rel in listOf("src/main.kt", "src/hooks/useThing.ts", ".vscode/extensions.json", "environment.ts", "README.md")) {
            assertTrue(rel, ApprovalRules.allows(rules, p, "edit", listOf(File(project, rel).path), policy))
        }
    }

    @Test
    fun `sensitive paths by segment`() {
        assertTrue(PathPolicy.isSensitive(".claude"))
        assertTrue(PathPolicy.isSensitive("a/b/.git/config"))
        assertTrue(PathPolicy.isSensitive(".env.test"))
        assertFalse(PathPolicy.isSensitive(""))
        assertFalse(PathPolicy.isSensitive("docs/env.md"))
        assertFalse(PathPolicy.isSensitive("tasks.json"))
    }

    @Test
    fun `fetch and search need project paths`() {
        val p = project.path
        val rules = listOf(ApprovalRule(p, "fetch"), ApprovalRule(p, "search"), ApprovalRule(p, "think"))
        assertFalse(ApprovalRules.allows(rules, p, "fetch", emptyList(), policy))
        assertFalse(ApprovalRules.allows(rules, p, "fetch", listOf("https://example.com"), policy))
        assertTrue(ApprovalRules.allows(rules, p, "fetch", listOf(File(project, "docs/a.md").path), policy))
        assertFalse(ApprovalRules.allows(rules, p, "search", emptyList(), policy))
        assertFalse(ApprovalRules.allows(rules, p, "search", listOf(home.path), policy))
        assertTrue(ApprovalRules.allows(rules, p, "think", emptyList(), policy))
    }

    // ---- file writes ----------------------------------------------------------------------

    @Test
    fun `an atomic write keeps the file's exec bit`() {
        val script = File(project, "run.sh").apply { writeText("#!/bin/sh\necho old\n"); setExecutable(true, false) }
        ChatSession.writeAtomically(script, "#!/bin/sh\necho new\n")
        assertEquals("#!/bin/sh\necho new\n", script.readText())
        assertTrue(script.canExecute())
        val plain = File(project, "a.txt").apply { writeText("x"); setExecutable(false, false) }
        ChatSession.writeAtomically(plain, "y")
        assertFalse(plain.canExecute())
    }

    // ---- terminals ------------------------------------------------------------------------

    @Test
    fun `terminal output keeps the end and starts at a character`() {
        val out = TerminalOutput(10)
        out.append("hello ".toByteArray())
        assertEquals("hello ", out.text())
        assertFalse(out.truncated)
        out.append("wörld!".toByteArray()) // ö is two bytes: 13 bytes in all
        val text = out.text()
        assertTrue(out.truncated)
        assertTrue(text, "hello wörld!".endsWith(text))
        assertTrue(text.toByteArray().size <= 10)
        // A chunk bigger than the limit alone.
        val big = TerminalOutput(4)
        big.append("abcdefgh".toByteArray())
        assertEquals("efgh", big.text())
    }

    @Test
    fun `terminal output cut inside a character drops the partial one`() {
        val out = TerminalOutput(4)
        out.append("aé€".toByteArray()) // 1 + 2 + 3 bytes: the last 4 start inside é
        assertEquals("€", out.text())
        val five = TerminalOutput(5)
        five.append("aé€".toByteArray()) // the last 5 start at é
        assertEquals("é€", five.text())
        // A character still arriving isn't shown half.
        val partial = TerminalOutput(100)
        val euro = "€".toByteArray()
        partial.append("x".toByteArray() + euro.copyOf(2))
        assertEquals("x", partial.text())
        partial.append(euro, 2, 1)
        assertEquals("x€", partial.text())
    }

    @Test
    fun `many small appends stay within the limit`() {
        val out = TerminalOutput(1000)
        repeat(5000) { out.append("line $it\n".toByteArray()) }
        val text = out.text()
        assertTrue(text.toByteArray().size <= 1000)
        assertTrue(text.endsWith("line 4999\n"))
    }

    @Test
    fun `terminal limits and command lines`() {
        assertEquals(TerminalLimits.DEFAULT_BYTES, TerminalLimits.outputLimit(null))
        assertEquals(TerminalLimits.MAX_BYTES, TerminalLimits.outputLimit(Long.MAX_VALUE))
        assertEquals(1, TerminalLimits.outputLimit(0))
        assertEquals(listOf("-c", "P; npm test -- --watch=false"), TerminalLimits.shellArgs("P", "npm test -- --watch=false", emptyList()))
        assertEquals(listOf("-c", "P; exec \"\$0\" \"\$@\"", "git", "log", "a b"), TerminalLimits.shellArgs("P", "git", listOf("log", "a b")))
    }

    @Test
    fun `exit statuses`() {
        assertEquals(TerminalExit(0, null), TerminalExit.of(0, killed = false))
        assertEquals(TerminalExit(null, "SIGTERM"), TerminalExit.of(143, killed = true))
        assertEquals(TerminalExit(130, null), TerminalExit.of(130, killed = false))
        assertEquals(TerminalExit(null, "SIGKILL"), TerminalExit.of(137, killed = false))
        assertEquals("Exit 2", TerminalExit(2, null).label)
    }

    /** A process whose output is [stdout] and which exits with [code] once that's read (or when destroyed). */
    private class FakeProcess(stdout: ByteArray, private val code: Int = 0) : Process() {
        private val input = ByteArrayInputStream(stdout)
        val stdin = ByteArrayOutputStream()
        @Volatile var destroyed = false
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = code
        override fun exitValue(): Int = code
        override fun destroy() { destroyed = true }
    }

    @Test
    fun `an ACP terminal collects output and exit status`() = runBlocking {
        val out = "x".repeat(50) + "tail\n"
        val exited = mutableListOf<String>()
        val t = AcpTerminal("t1", "make", FakeProcess(out.toByteArray(), code = 3), limit = 20, ownGroup = false, post = { it.run() }) {
            exited += it.id
        }
        val status = withTimeout(5_000) { t.exited.await() }
        assertEquals(TerminalExit(3, null), status)
        assertEquals(TerminalExit(3, null), t.exitStatus)
        assertTrue(t.output.truncated)
        assertTrue(t.output.text().endsWith("tail\n"))
        // UI state follows (posted after the deferred completes).
        val deadline = System.currentTimeMillis() + 5_000
        while (exited.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(listOf("t1"), exited)
        assertFalse(t.running)
        assertTrue(t.text.endsWith("tail\n"))
        // Killing a finished one does nothing.
        t.kill()
    }

    @Test
    fun `process group id from stat`() {
        assertEquals(1234, ProcessGroup.parsePgrp("1234 (node (dev) x) S 1 1234 1234 0 -1"))
        assertNull(ProcessGroup.parsePgrp("garbage"))
        assertEquals(listOf("/bin/setsid", "bash"), ProcessGroup.command(File("/bin/setsid"), listOf("bash")))
        assertEquals(listOf("bash"), ProcessGroup.command(null, listOf("bash")))
    }

    // ---- MCP servers ----------------------------------------------------------------------

    @Test
    fun `mcp servers in ACP form`() {
        val servers = listOf(
            McpServer("db", command = "npx", args = listOf("-y", "db-mcp"), env = mapOf("TOKEN" to "t")),
            McpServer("web", url = "https://example.com/mcp"),
            McpServer("gem", command = "gem-mcp"),
            McpServer("broken"),
        )
        val stdio = McpServers.toAcp(servers, McpServers.Caps(), skip = setOf("gem")) { "/usr/bin/$it" }
        assertEquals(1, stdio.size)
        val db = stdio[0].jsonObject
        assertEquals("db", db["name"]!!.jsonPrimitive.content)
        assertEquals("/usr/bin/npx", db["command"]!!.jsonPrimitive.content)
        assertEquals(listOf("-y", "db-mcp"), db["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        val env = db["env"]!!.jsonArray.single().jsonObject
        assertEquals("TOKEN", env["name"]!!.jsonPrimitive.content)
        assertEquals("t", env["value"]!!.jsonPrimitive.content)

        val withHttp = McpServers.toAcp(servers, McpServers.Caps(http = true))
        val web = withHttp.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "web" }
        assertEquals("http", web["type"]!!.jsonPrimitive.content)
        assertEquals(3, withHttp.size)
    }

    @Test
    fun `mcp capabilities and already merged servers`() {
        val caps = McpServers.caps(buildJsonObject { putJsonObject("mcpCapabilities") { put("http", true) } })
        assertEquals(McpServers.Caps(http = true, sse = false), caps)
        assertEquals(McpServers.Caps(), McpServers.caps(null))
        val settings = File(project, ".gemini/settings.json").apply { parentFile.mkdirs(); writeText("""{"theme":"x","mcpServers":{"db":{},"gem":{}}}""") }
        assertEquals(setOf("db", "gem"), McpServers.namesIn(settings))
        assertEquals(emptySet<String>(), McpServers.namesIn(File(project, "missing.json")))
    }

    // ---- streaming markdown ---------------------------------------------------------------

    @Test
    fun `stream split ends after finished blocks only`() {
        assertEquals(0, StreamSplit.stableEnd("Hello, wor"))
        val text = "First paragraph.\n\nSecond, still being wri"
        assertEquals("First paragraph.\n\n".length, StreamSplit.stableEnd(text))
        // Not inside a code fence, even across blank lines.
        val fenced = "Intro\n\n```kotlin\nval a = 1\n\nval b = 2\n"
        assertEquals("Intro\n\n".length, StreamSplit.stableEnd(fenced))
        val closed = fenced + "```\n\nAfter"
        assertEquals(closed.length - "After".length, StreamSplit.stableEnd(closed))
        // An indented continuation (part of a list item) doesn't end the list.
        val list = "1. one\n\n   more of one\n"
        assertEquals(0, StreamSplit.stableEnd(list))
        // Not inside <details>.
        val details = "<details>\n<summary>S</summary>\n\nBody\n\nMore"
        assertEquals(0, StreamSplit.stableEnd(details))
    }

    // ---- storage --------------------------------------------------------------------------

    @Test
    fun `long tool output keeps its start and end`() {
        val text = "HEAD" + "x".repeat(50_000) + "ERROR at end"
        val capped = ChatStore.capText(text, 1_000)
        assertTrue(capped.startsWith("HEAD"))
        assertTrue(capped.endsWith("ERROR at end"))
        assertTrue(capped.contains("characters left out"))
        assertTrue(capped.length < 1_100)
        assertEquals("short", ChatStore.capText("short", 1_000))
    }

    @Test
    fun `usage, permission details and terminal output survive a save`() {
        val permission = ChatItem.Permission(
            "Run tests", listOf(PermissionOption("a", "Allow", "allow_once")), kotlinx.coroutines.CompletableDeferred(), "execute",
            PermissionDetails("npm test", listOf("/p/a.kt"), listOf(ToolContent.Diff("/p/a.kt", "a", "b"))),
        )
        val tool = ChatItem.ToolCall("t").apply { content = listOf(ToolContent.Terminal("term-1", "done\n", "Exit 0")) }
        val saved = SavedChat("c", "Claude", "/p", "T", null, 1L, listOf(permission, tool), usage = Usage(1000, 200_000, 0.42, "USD"))
        val back = ChatStore.decode(Json.parseToJsonElement(ChatStore.encode(saved).toString()).jsonObject)
        assertEquals(Usage(1000, 200_000, 0.42, "USD"), back.usage)
        val p = back.items[0] as ChatItem.Permission
        assertEquals("npm test", p.details.command)
        assertEquals(listOf("/p/a.kt"), p.details.paths)
        assertEquals("execute", p.toolKind)
        assertTrue(p.details.diffs.isEmpty()) // diffs travel only to paired devices
        val wire = ChatStore.decodeItem(ChatStore.encodeItem(permission, wire = true), wire = true) as ChatItem.Permission
        assertEquals("b", wire.details.diffs.single().newText)
        val terminal = (back.items[1] as ChatItem.ToolCall).content.single() as ToolContent.Terminal
        assertEquals(ToolContent.Terminal("term-1", "done\n", "Exit 0"), terminal)
    }

    @Test
    fun `permission summary for notifications`() {
        assertEquals("$ npm test", PermissionDetails("npm test\nnpm run lint").summary("/p").substringBefore(" …"))
        assertEquals("src/a.kt, /etc/hosts", PermissionDetails(paths = listOf("/p/src/a.kt", "/etc/hosts")).summary("/p"))
        assertEquals("a, b, c and 2 more", PermissionDetails(paths = listOf("/p/a", "/p/b", "/p/c", "/p/d", "/p/e")).summary("/p"))
        assertEquals("", PermissionDetails().summary("/p"))
    }

    @Test
    fun `turn costs and totals`() {
        assertEquals(0.25, ChatSession.turnCost(1.0, 1.25)!!, 1e-9)
        assertNull(ChatSession.turnCost(null, 1.0))
        assertNull(ChatSession.turnCost(2.0, 0.5)) // the agent started counting again
        val turns = listOf(
            ChatTurn(1, "a", 0, 10, cost = 0.5, currency = "USD"),
            ChatTurn(2, "b", 10, 30, cost = 0.25),
            ChatTurn(3, "c", 30, 40, cost = 3.0, currency = "EUR"),
            ChatTurn(4, "d", 40, 50),
        )
        val stats = Checkpoints.stats(turns)
        assertEquals(4, stats.turns)
        assertEquals(0.75, stats.costs["USD"]!!, 1e-9)
        assertEquals(3.0, stats.costs["EUR"]!!, 1e-9)
        val parsed = Checkpoints.parseTurns("""[{"index":1,"prompt":"a","startedAt":0,"endedAt":5,"cost":0.1,"currency":"USD"}]""")!!
        assertEquals(0.1, parsed.single().cost!!, 1e-9)
        assertEquals("USD", parsed.single().currency)
        assertEquals(mapOf("USD" to 1.0, "EUR" to 2.0), Checkpoints.addCosts(mapOf("USD" to 0.5), mapOf("USD" to 0.5, "EUR" to 2.0)))
    }

    // ---- mentions and locations -----------------------------------------------------------

    @Test
    fun `file mentions are bounded and ranked`() = runBlocking {
        File(project, "src/deep/a").mkdirs()
        File(project, "src/main.kt").writeText("")
        File(project, "src/deep/a/main_helper.kt").writeText("")
        File(project, "node_modules/x").mkdirs()
        File(project, "node_modules/x/main.js").writeText("")
        File(project, ".hidden").writeText("")
        val all = FileMentions.list(project)
        assertEquals(setOf("src/main.kt", "src/deep/a/main_helper.kt"), all.toSet())
        assertEquals(listOf("src/main.kt", "src/deep/a/main_helper.kt"), FileMentions.match(all, "main"))
        assertEquals(1, FileMentions.list(project, maxFiles = 1).size)
        assertEquals(emptyList<String>(), FileMentions.list(project, maxDepth = 0))
    }

    @Test
    fun `a cancelled mention walk stops`() = runBlocking {
        val job = Job().apply { cancel() }
        try {
            kotlinx.coroutines.withContext(job) { FileMentions.list(project) }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `tool call locations`() {
        val cwd = File("/p")
        assertEquals(File("/p/src/a.kt") to 12, parseLocation("src/a.kt:12", cwd))
        assertEquals(File("/abs/b.kt") to null, parseLocation("/abs/b.kt", cwd))
        assertEquals(File("/abs/b.kt") to null, parseLocation("/abs/b.kt:0", cwd))
        assertNull(parseLocation("rel.kt", null))
    }

    @Test
    fun `queued messages remember whether they were shown`() {
        assertFalse(QueuedMessage("a").shown)
        assertTrue(QueuedMessage("a", shown = true).shown)
    }

}
