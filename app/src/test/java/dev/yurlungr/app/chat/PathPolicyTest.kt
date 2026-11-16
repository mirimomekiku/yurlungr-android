package dev.yurlungr.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import dev.yurlungr.app.acp.AcpException
import java.io.File

/** Where agents may read/write, and what a standing approval rule may cover. */
class PathPolicyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val data by lazy { tmp.newFolder("data") }
    private val files by lazy { File(data, "files").apply { mkdirs() } }
    private val home by lazy { File(files, "home").apply { mkdirs() } }
    private val project by lazy { File(home, "projects/app").apply { mkdirs() } }
    private val policy by lazy { PathPolicy(data, files, home) }

    private fun f(base: File, rel: String) = File(base, rel).path

    @Test
    fun `app settings and private folders are off limits`() {
        for (rel in listOf("shared_prefs/approval_rules.xml", "shared_prefs/remote_trust.xml", "databases/x.db", "app_chats/c1.json")) {
            assertFalse(rel, policy.allowsAccess(policy.resolve(f(data, rel))!!))
        }
        assertFalse(policy.allowsAccess(policy.resolve(f(files, "checkpoints/index"))!!))
    }

    @Test
    fun `the runtime, home and outside paths are allowed`() {
        assertTrue(policy.allowsAccess(policy.resolve(f(project, "src/main.kt"))!!))
        assertTrue(policy.allowsAccess(policy.resolve(f(files, "usr/bin/node"))!!))
        assertTrue(policy.allowsAccess(policy.resolve(f(tmp.root, "elsewhere/notes.txt"))!!))
    }

    @Test
    fun `dot-dot can't climb out to the app's settings`() {
        val sneaky = f(project, "../../../../shared_prefs/approval_rules.xml")
        assertFalse(policy.allowsAccess(policy.resolve(sneaky)!!))
    }

    @Test
    fun `relative paths aren't accepted`() {
        assertEquals(null, policy.resolve("shared_prefs/x.xml"))
        assertEquals(null, policy.resolve(""))
    }

    @Test
    fun `auto approval covers only files inside the project`() {
        assertTrue(policy.allowsAutoApproval(project, listOf(f(project, "src/a.kt"), f(project, "README.md"))))
        assertTrue(policy.allowsAutoApproval(project, emptyList()))
        assertFalse(policy.allowsAutoApproval(project, listOf(f(project, "src/a.kt"), f(home, ".bashrc"))))
        assertFalse(policy.allowsAutoApproval(project, listOf(f(home, "projects/other/a.kt"))))
        assertFalse(policy.allowsAutoApproval(project, listOf(f(data, "shared_prefs/approval_rules.xml"))))
        assertFalse(policy.allowsAutoApproval(project, listOf(f(project, "../app-secrets/key"))))
        assertFalse(policy.allowsAutoApproval(project, listOf("src/relative.kt")))
    }

    @Test
    fun `git internals and the home folder itself are never auto approved`() {
        assertFalse(policy.allowsAutoApproval(project, listOf(f(project, ".git/hooks/pre-commit"))))
        assertTrue(policy.allowsAutoApproval(project, listOf(f(project, ".github/workflows/ci.yml"))))
        assertFalse(policy.allowsAutoApproval(home, listOf(f(home, "notes.md"))))
        assertFalse(policy.allowsAutoApproval(files, emptyList()))
    }

    @Test
    fun `isUnder matches whole segments`() {
        assertTrue(PathPolicy.isUnder("/p/app/x", "/p/app"))
        assertTrue(PathPolicy.isUnder("/p/app", "/p/app/"))
        assertFalse(PathPolicy.isUnder("/p/app-secrets/x", "/p/app"))
    }

    // ---- approval rules -------------------------------------------------------------------

    private val p get() = project.path

    @Test
    fun `rule approves only its kind and project`() {
        val rules = listOf(ApprovalRule(p, "edit"))
        val inside = listOf(f(project, "a.kt"))
        assertTrue(ApprovalRules.allows(rules, p, "edit", inside, policy))
        assertFalse(ApprovalRules.allows(rules, p, "execute", inside, policy))
        assertFalse(ApprovalRules.allows(rules, f(home, "projects/other"), "edit", inside, policy))
        assertFalse(ApprovalRules.allows(rules, p, null, inside, policy))
    }

    @Test
    fun `an edit rule doesn't approve edits outside the project`() {
        val rules = listOf(ApprovalRule(p, "edit"))
        assertFalse(ApprovalRules.allows(rules, p, "edit", listOf(f(data, "shared_prefs/approval_rules.xml")), policy))
        assertFalse(ApprovalRules.allows(rules, p, "edit", listOf(f(home, ".bashrc")), policy))
    }

    @Test
    fun `file kinds need named files`() {
        val rules = listOf(ApprovalRule(p, "edit"), ApprovalRule(p, "read"), ApprovalRule(p, "search"))
        assertFalse(ApprovalRules.allows(rules, p, "edit", emptyList(), policy))
        assertFalse(ApprovalRules.allows(rules, p, "read", emptyList(), policy))
        assertTrue(ApprovalRules.allows(rules, p, "search", emptyList(), policy))
    }

    @Test
    fun `star scope applies everywhere but still checks paths`() {
        val rules = listOf(ApprovalRule("*", "read"))
        assertTrue(ApprovalRules.allows(rules, p, "read", listOf(f(project, "a.kt")), policy))
        assertFalse(ApprovalRules.allows(rules, p, "read", listOf(f(home, ".codex/auth.json")), policy))
    }

    @Test
    fun `other kinds are never covered`() {
        assertFalse(ApprovalRules.allows(listOf(ApprovalRule("*", "other")), p, "other", emptyList(), policy))
        assertFalse(ApprovalRules.allows(listOf(ApprovalRule("*", "delete")), p, "delete", listOf(f(project, "a")), policy))
    }

    // ---- fs helpers -------------------------------------------------------------------------

    @Test
    fun `line reads are capped`() {
        val log = File(tmp.root, "big.log").apply { writeText((1..1000).joinToString("\n") { "line $it" }) }
        assertEquals("line 3\nline 4", ChatSession.readLines(log, 3, 2))
        assertEquals("line 999\nline 1000", ChatSession.readLines(log, 999, null))
        try {
            ChatSession.readLines(log, 1, null, maxChars = 100)
            fail("expected a size error")
        } catch (e: AcpException) {
            assertTrue(e.message!!.contains("limit"))
        }
    }

    @Test
    fun `atomic write replaces the file and leaves no temp file`() {
        val dir = tmp.newFolder("w")
        val target = File(dir, "a.txt").apply { writeText("old") }
        ChatSession.writeAtomically(target, "new ✓")
        assertEquals("new ✓", target.readText())
        assertEquals(listOf("a.txt"), dir.list()!!.toList())
        val nested = File(dir, "x/y/b.txt")
        ChatSession.writeAtomically(nested, "b")
        assertEquals("b", nested.readText())
    }

    // ---- other chat fixes ----------------------------------------------------------------

    @Test
    fun `search snippet survives text whose lowercase is longer`() {
        val text = "İİİİ ".repeat(30) + "needle here"
        val at = text.indexOf("NEEDLE", ignoreCase = true)
        val s = ChatSearch.snippet(text, at, 6)
        assertTrue(s, s.contains("needle"))
        // Out-of-range input doesn't throw either.
        ChatSearch.snippet("short", 10, 6)
    }

    @Test
    fun `queued messages survive a save`() {
        val chat = SavedChat("c1", "Claude", "/x", "t", null, 1L, emptyList(), queuedTexts = listOf("one", "two"))
        val back = ChatStore.decode(Json.parseToJsonElement(ChatStore.encode(chat).toString()).jsonObject)
        assertEquals(listOf("one", "two"), back.queuedTexts)
        val none = ChatStore.decode(Json.parseToJsonElement(ChatStore.encode(chat.copy(queuedTexts = emptyList())).toString()).jsonObject)
        assertEquals(emptyList<String>(), none.queuedTexts)
    }

    @Test
    fun `artifact preview stays in its folder`() {
        assertTrue(Artifacts.isInside("file", "/p/app/index.html", "/p/app"))
        assertTrue(Artifacts.isInside("file", "/p/app/img/a.png", "/p/app"))
        assertFalse(Artifacts.isInside("file", "/p/app-secrets/key.txt", "/p/app"))
        assertFalse(Artifacts.isInside("file", "/p/app/../secrets", "/p/app"))
        assertFalse(Artifacts.isInside("https", "/p/app/x", "/p/app"))
        assertFalse(Artifacts.isInside("file", "/x", ""))
    }
}
