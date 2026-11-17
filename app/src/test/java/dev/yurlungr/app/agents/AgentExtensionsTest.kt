package dev.yurlungr.app.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentExtensionsTest {
    private fun tmp(): File = Files.createTempDirectory("ext").toFile().also { it.deleteOnExit() }

    @Test fun `frontmatter name and description are read, quotes and folded values handled`() {
        val m = AgentExtensions.parseFrontmatter("---\nname: \"pdf-tools\"\ndescription: >\n  Work with PDFs\n  and more\nother: x\n---\nbody")
        assertEquals("pdf-tools", m["name"])
        assertEquals("Work with PDFs", m["description"])
        assertTrue(AgentExtensions.parseFrontmatter("no frontmatter").isEmpty())
    }

    @Test fun `skills are found one or two levels down, hidden folders skipped`() {
        val root = tmp()
        File(root, "a").mkdirs(); File(root, "a/SKILL.md").writeText("---\nname: alpha\ndescription: first\n---\n")
        File(root, "repo/inner").mkdirs(); File(root, "repo/inner/SKILL.md").writeText("# no frontmatter")
        File(root, ".hidden").mkdirs(); File(root, ".hidden/SKILL.md").writeText("x")
        File(root, "empty").mkdirs()
        val skills = AgentExtensions.scanSkills(root, ExtScope.Project, ".claude/skills")
        assertEquals(listOf("alpha", "inner"), skills.map { it.name })
        assertEquals("first", skills.first().description)
    }

    @Test fun `command files become namespaced slash names and respect the format`() {
        val root = tmp()
        File(root, "ns").mkdirs()
        File(root, "review.md").writeText("x"); File(root, "ns/deploy.md").writeText("x"); File(root, "skip.toml").writeText("x"); File(root, "notes.txt").writeText("x")
        val md = AgentExtensions.scanCommands(root, CommandFormat.Markdown, ExtScope.User, "~/c").map { it.name }
        assertEquals(listOf("ns:deploy", "review"), md)
        assertEquals(listOf("skip"), AgentExtensions.scanCommands(root, CommandFormat.Toml, ExtScope.User, "~/c").map { it.name })
    }

    @Test fun `new skills and commands are created from templates and never overwrite`() {
        val root = tmp()
        val skill = AgentExtensions.createSkill(root, "code-review", "Reviews code").getOrThrow()
        assertTrue(skill.readText().startsWith("---\nname: code-review\ndescription: Reviews code\n---"))
        assertTrue(AgentExtensions.createSkill(root, "code-review", "again").isFailure)
        assertTrue(AgentExtensions.createSkill(root, "Bad Name", "x").isFailure)
        assertTrue(AgentExtensions.createSkill(root, "../escape", "x").isFailure)
        val toml = AgentExtensions.createCommand(root, "plan", CommandFormat.Toml, "Make a \"plan\"").getOrThrow()
        assertTrue(toml.name.endsWith(".toml") && toml.readText().contains("description = \"Make a \\\"plan\\\"\"") && toml.readText().contains("{{args}}"))
        assertTrue(AgentExtensions.createCommand(root, "fix", CommandFormat.Markdown, "d").getOrThrow().readText().contains("\$ARGUMENTS"))
    }

    @Test fun `delete only works inside the skills folders and never on a root`() {
        val root = tmp(); val outside = tmp()
        val skill = AgentExtensions.createSkill(root, "x", "d").getOrThrow().parentFile
        File(outside, "keep.txt").writeText("keep")
        assertTrue(AgentExtensions.delete(File(outside, "keep.txt"), listOf(root)).isFailure)
        assertTrue(File(outside, "keep.txt").exists())
        assertTrue(AgentExtensions.delete(root, listOf(root)).isFailure)
        assertTrue(root.exists())
        assertTrue(AgentExtensions.delete(skill, listOf(root)).isSuccess)
        assertFalse(skill.exists())
    }

    @Test fun `plugin commands quote a plain argument and refuse anything shell-like`() {
        assertEquals("claude plugin install 'foo@market'", AgentExtensions.pluginCommand("claude plugin install %s", "foo@market"))
        assertEquals("pi install 'https://github.com/a/b'", AgentExtensions.pluginCommand("pi install %s", "https://github.com/a/b"))
        assertNull(AgentExtensions.pluginCommand("claude plugin install %s", "x; rm -rf ~"))
        assertNull(AgentExtensions.pluginCommand("claude plugin install %s", "a b"))
        assertNull(AgentExtensions.pluginCommand("claude plugin install %s", "\$(id)"))
        assertEquals("claude plugin list", AgentExtensions.pluginCommand("claude plugin list", null))
    }

    @Test fun `clone commands accept plain git addresses only`() {
        val root = File("/h/.claude/skills")
        val ok = AgentExtensions.cloneCommand("https://github.com/anthropics/skills.git", root)!!
        assertTrue(ok.contains("git clone --depth 1 'https://github.com/anthropics/skills.git' '/h/.claude/skills/skills'"))
        assertNotNull(AgentExtensions.cloneCommand("git@github.com:owner/repo.git", root))
        assertNull(AgentExtensions.cloneCommand("https://x.com/a b", root))
        assertNull(AgentExtensions.cloneCommand("https://x.com/a;rm -rf /", root))
        assertNull(AgentExtensions.cloneCommand("file:///etc", root))
        assertNull(AgentExtensions.cloneCommand("https://x.com/a'b", root))
    }

    @Test fun `every layout has somewhere for skills and the unmanaged agents have none`() {
        for (a in Agent.entries) {
            val l = AgentExtensions.layout(a) ?: continue
            assertTrue("${a.name} skills folder", l.skillsProject.isNotEmpty() || l.skillsUser.isNotEmpty())
            l.plugins?.let { p -> assertTrue("${a.name} plugin commands start with its binary", listOfNotNull(p.list, p.install, p.remove, p.marketplaceAdd).all { it.startsWith(p.bin + " ") }) }
        }
        assertNull(AgentExtensions.layout(Agent.Goose))
        assertNotNull(AgentExtensions.layout(Agent.Claude))
    }

    @Test fun `plugin arguments can't start with a dash`() {
        assertNull(AgentExtensions.pluginCommand("claude plugin install %s", "--help"))
        assertNull(AgentExtensions.pluginCommand("claude plugin install %s", "-x"))
        assertEquals("claude plugin install 'a-b@m'", AgentExtensions.pluginCommand("claude plugin install %s", "a-b@m"))
    }

    @Test fun `deleting a skill removes links inside it without following them`() {
        val tmp = Files.createTempDirectory("ext").toFile()
        try {
            val root = File(tmp, "skills").apply { mkdirs() }
            val precious = File(tmp, "home").apply { mkdirs() }
            val keep = File(precious, "keep.txt").apply { writeText("x") }
            val skill = File(root, "evil").apply { mkdirs() }
            File(skill, "SKILL.md").writeText("---\nname: evil\n---\n")
            val linked = runCatching { Files.createSymbolicLink(File(skill, "data").toPath(), precious.toPath()) }.isSuccess
            org.junit.Assume.assumeTrue("symlinks unavailable here", linked)
            assertTrue(AgentExtensions.delete(skill, listOf(root)).isSuccess)
            assertFalse(skill.exists())
            assertTrue("the link's target survives", keep.isFile)
        } finally { tmp.deleteRecursively() }
    }

    @Test fun `delete refuses anything outside the roots, and the root itself`() {
        val tmp = Files.createTempDirectory("ext").toFile()
        try {
            val root = File(tmp, "skills").apply { mkdirs() }
            val outside = File(tmp, "other").apply { mkdirs() }
            assertTrue(AgentExtensions.delete(outside, listOf(root)).isFailure)
            assertTrue(AgentExtensions.delete(root, listOf(root)).isFailure)
            assertTrue(AgentExtensions.delete(File(root, "../other"), listOf(root)).isFailure)
            assertTrue(outside.isDirectory && root.isDirectory)
        } finally { tmp.deleteRecursively() }
    }
}
