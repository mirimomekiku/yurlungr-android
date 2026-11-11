package dev.yurlungr.app.projects

import dev.yurlungr.app.runtime.Storage
import dev.yurlungr.app.runtime.ToolSetup
import dev.yurlungr.app.web.LocalServers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectToolsTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun write(path: String, text: String = "") = File(tmp.root, path).apply { parentFile?.mkdirs(); writeText(text) }

    @Test fun `automatic setup remembers the stacks, not what's still missing`() {
        val a = DetectedStack("node", "Node.js (pnpm)", File("/p"), "package.json", packages = listOf("nodejs-lts", "pnpm"), steps = listOf("pnpm install"), installed = false)
        // Halfway through the install (Node.js done, deps not yet) it's the same need: no second run.
        assertEquals(autoSetupSignature(listOf(a)), autoSetupSignature(listOf(a.copy(installed = true, packages = listOf("pnpm")))))
        val b = a.copy(id = "python", dir = File("/p/api"))
        assertEquals(autoSetupSignature(listOf(a, b)), autoSetupSignature(listOf(b, a)))
    }

    @Test fun `du and dpkg status are read into sizes`() {
        assertEquals(mapOf("/h/.gradle/caches" to 2048L * 1024, "/h/.npm/_cacache" to 10L * 1024), Storage.parseDu("2048\t/h/.gradle/caches\n10\t/h/.npm/_cacache\n"))
        val status = "Package: ghc\nStatus: install ok installed\nInstalled-Size: 2000\n\nPackage: cabal-install\nStatus: deinstall ok config-files\nInstalled-Size: 99\n\nPackage: zig\nStatus: install ok installed\nInstalled-Size: 10\n"
        val sizes = Storage.parseDpkgStatus(status)
        assertEquals(setOf("ghc", "zig"), sizes.keys)
        assertEquals(2000L * 1024, Storage.packSize(ToolSetup.Haskell, sizes))
        assertEquals("1.5 GB", Storage.human(1536L * 1024 * 1024))
    }

    @Test fun `tasks come from package json, make, just and the stacks`() {
        write("package.json", """{"scripts": {"dev": "vite", "test": "vitest"}}""")
        write("pnpm-lock.yaml")
        write("Makefile", "VAR := 1\nbuild: deps\n\tgo build\n%.o: %.c\n\tcc\nmain.o: main.c\n.PHONY: build\ntest:\n\tgo test\n")
        write("justfile", "set shell := [\"bash\"]\nalias b := build\nbuild:\n  echo\ndeploy env:\n  echo {{env}}\n")
        val tasks = ProjectTasks.detect(tmp.root, StackDetector.detect(tmp.root))
        val commands = tasks.map { it.command }
        assertTrue(commands.containsAll(listOf("pnpm run dev", "pnpm run test", "make build", "make test", "just build", "just deploy")))
        assertFalse("files and pattern rules aren't commands", commands.any { it.contains("main.o") || it.contains("%") })
        assertFalse(commands.any { it == "just set" || it == "just alias" })
    }

    @Test fun `ripgrep output with colons in text parses, paths keep their own colons out`() {
        val hits = dev.yurlungr.app.projects.ProjectSearch.parse("./src/a.kt\u000012:val x = \"a:b\"\nREADME.md\u00003:# Title\nnot a hit\n")
        assertEquals(listOf(SearchHit("src/a.kt", 12, "val x = \"a:b\""), SearchHit("README.md", 3, "# Title")), hits)
        val args = ProjectSearch.args(File("/bin/rg"), "-x", regex = false)
        assertTrue("a query starting with - is a pattern, not a flag", args.indexOf("-e") == args.indexOf("-x") - 1)
        assertTrue("--fixed-strings" in args)
    }

    @Test fun `processes parse from ps, with arguments kept whole`() {
        val ps = LocalServers.parsePs("  123     1   3700  51200 node /p/node_modules/.bin/vite --port 5173\n  9  1 5 100 bash -l\nbad line\n")
        assertEquals(2, ps.size)
        assertEquals("node", ps[0].name)
        assertEquals("node /p/node_modules/.bin/vite --port 5173", ps[0].args)
        assertEquals("1h 1m", LocalServers.uptime(3700))
    }

    @Test fun `MCP servers round-trip, other settings are kept, and commands split like a shell`() {
        val root = Json.parseToJsonElement("""{"mcpServers": {"fs": {"command": "npx", "args": ["-y", "server-fs", "."], "env": {"A": "1"}},
            "web": {"type": "http", "url": "https://x.dev/mcp"}}, "other": true}""") as JsonObject
        val servers = ProjectMcp.parse(root)
        assertEquals(listOf("fs", "web"), servers.map { it.name })
        assertEquals(listOf("-y", "server-fs", "."), servers[0].args)
        assertTrue(servers[1].isRemote)
        assertEquals("https://x.dev/mcp", ProjectMcp.geminiEntry(servers[1])["httpUrl"]?.jsonPrimitive?.content)
        val merged = ProjectMcp.merged(root, mapOf("fs" to ProjectMcp.claudeEntry(servers[0])))
        assertEquals(JsonPrimitive(true), merged["other"])
        assertEquals(setOf("fs"), merged["mcpServers"]!!.jsonObject.keys)
        assertEquals(listOf("npx", "-y", "my server", "--x", ""), ProjectMcp.splitArgs("npx -y 'my server' --x \"\""))
    }
}
