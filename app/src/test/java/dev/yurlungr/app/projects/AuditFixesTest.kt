package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuditFixesTest {
    @Test fun `every unmerged pair is a conflict, not added or deleted`() {
        for (pair in listOf("UU", "AA", "DD", "AU", "UA", "DU", "UD")) {
            val c = GitChange("f.txt", pair[0], pair[1])
            assertTrue(pair, c.isConflict)
            assertEquals(pair, "conflict", c.label)
            assertFalse("$pair isn't staged", c.isStaged)
        }
        assertEquals("added", GitChange("f", 'A', ' ').label)
        assertEquals("deleted", GitChange("f", ' ', 'D').label)
        assertFalse(GitChange("f", 'A', 'M').isConflict)
        val status = GitStatus(isRepo = true, changes = listOf(GitChange("a", 'A', 'A'), GitChange("b", 'M', ' ')))
        assertEquals(listOf("a"), status.conflicts)
    }

    @Test fun `conflict markers are found only as git writes them`() {
        for (line in listOf("+<<<<<<< HEAD", "+>>>>>>> feature", "+<<<<<<<", "+>>>>>>>")) assertTrue(line, CONFLICT_MARKER.containsMatchIn(line))
        for (line in listOf(" <<<<<<< HEAD", "-<<<<<<< HEAD", "+<<<<<<<<<< not a marker", "+text <<<<<<< x", "+=======")) assertFalse(line, CONFLICT_MARKER.containsMatchIn(line))
    }

    @Test fun `inside means the folder or below it, not a sibling with the same prefix`() {
        val root = File("/data/home/projects")
        assertTrue(LastProject.isInside(File("/data/home/projects"), root))
        assertTrue(LastProject.isInside(File("/data/home/projects/app"), root))
        assertFalse(LastProject.isInside(File("/data/home/projects2"), root))
        assertFalse(LastProject.isInside(File("/data/home"), root))
    }

    @Test fun `installer keeps the single-program agents where the app looks`() {
        val script = File("src/main/assets/agent-install.sh").readText()
        // The app checks libexec/yurlungr/<agent>/<agent> for these (Agents.requiredFiles).
        for (agent in listOf("copilot", "goose", "opencode")) assertTrue(agent, script.contains("wrap $agent \"\$DEST/$agent\""))
        assertTrue("normalizes their layout", script.contains("mv -f \"\$main\" \"\$NEW/\$AGENT\""))
        assertTrue("a half-patched bundle isn't swapped in", script.indexOf("couldn't be patched") < script.indexOf("mv \"\$NEW\" \"\$DEST\""))
    }

    @Test fun `pack removal checks what apt would take before removing`() {
        val script = File("src/main/assets/pack-remove.sh").readText()
        assertTrue(script.indexOf("apt-get -s remove") < script.indexOf("apt-get remove -y"))
        assertTrue(script.contains("blocked"))
    }
}
