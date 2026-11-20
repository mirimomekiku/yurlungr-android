package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HunkPatchTest {
    private val header = "diff --git a/src/a.txt b/src/a.txt\nindex 1111111..2222222 100644\n--- a/src/a.txt\n+++ b/src/a.txt\n"

    private val twoHunks = header +
        "@@ -1,3 +1,3 @@\n" +
        " one\n-two\n+TWO\n three\n" +
        "@@ -10,3 +10,4 @@ fun x()\n" +
        " ten\n eleven\n+eleven and a half\n twelve\n"

    @Test fun `splits a diff into header and hunks`() {
        val p = HunkPatch.parse(twoHunks)!!
        assertEquals(4, p.header.size)
        assertEquals(2, p.hunks.size)
        assertEquals("@@ -1,3 +1,3 @@", p.hunks[0][0])
        assertEquals(listOf("@@ -10,3 +10,4 @@ fun x()", " ten", " eleven", "+eleven and a half", " twelve"), p.hunks[1])
    }

    @Test fun `a hunk's patch is the header plus only that hunk`() {
        val second = HunkPatch.forHunk(twoHunks, 1)!!
        assertEquals(header + "@@ -10,3 +10,4 @@ fun x()\n ten\n eleven\n+eleven and a half\n twelve\n", second)
        val first = HunkPatch.forHunk(twoHunks, 0)!!
        assertTrue(first.contains("-two\n+TWO\n"))
        assertTrue(!first.contains("eleven"))
        assertNull(HunkPatch.forHunk(twoHunks, 2))
    }

    @Test fun `additions only and deletions only`() {
        val adds = header + "@@ -0,0 +1,2 @@\n+a\n+b\n@@ -5,0 +8,1 @@\n+c\n"
        assertEquals(2, HunkPatch.parse(adds)!!.hunks.size)
        val dels = header + "@@ -1,2 +0,0 @@\n-a\n-b\n@@ -7 +4,0 @@\n-c\n"
        val p = HunkPatch.parse(dels)!!
        assertEquals(2, p.hunks.size)
        assertEquals(listOf("@@ -7 +4,0 @@", "-c"), p.hunks[1])
    }

    @Test fun `no newline at end of file stays with its hunk`() {
        val diff = header + "@@ -1,2 +1,2 @@\n a\n-b\n\\ No newline at end of file\n+b\n"
        val p = HunkPatch.parse(diff)!!
        assertEquals(listOf("@@ -1,2 +1,2 @@", " a", "-b", "\\ No newline at end of file", "+b"), p.hunks.single())
        val both = header + "@@ -3,2 +3,2 @@\n x\n-y\n\\ No newline at end of file\n+z\n\\ No newline at end of file\n"
        assertEquals(6, HunkPatch.parse(both)!!.hunks.single().size)
        assertTrue(HunkPatch.forHunk(both, 0)!!.endsWith("+z\n\\ No newline at end of file\n"))
    }

    @Test fun `CRLF lines keep their CR`() {
        val diff = header + "@@ -1,2 +1,2 @@\n a\r\n-b\r\n+c\r\n"
        val patch = HunkPatch.forHunk(diff, 0)!!
        assertTrue(patch.contains(" a\r\n-b\r\n+c\r\n"))
    }

    @Test fun `an empty line inside a hunk is an empty context line`() {
        // diff.suppressBlankEmpty writes blank context lines without their space.
        val diff = header + "@@ -1,3 +1,3 @@\n a\n\n-b\n+c\n"
        assertEquals(listOf("@@ -1,3 +1,3 @@", " a", "", "-b", "+c"), HunkPatch.parse(diff)!!.hunks.single())
    }

    @Test fun `changes that aren't a plain edit can't be staged by hunk`() {
        assertNull("new file", HunkPatch.parse("diff --git a/n b/n\nnew file mode 100644\nindex 0000000..1111111\n--- /dev/null\n+++ b/n\n@@ -0,0 +1 @@\n+x\n"))
        assertNull("deleted", HunkPatch.parse("diff --git a/n b/n\ndeleted file mode 100644\n--- a/n\n+++ /dev/null\n@@ -1 +0,0 @@\n-x\n"))
        assertNull("renamed", HunkPatch.parse("diff --git a/o b/n\nsimilarity index 90%\nrename from o\nrename to n\n--- a/o\n+++ b/n\n@@ -1 +1 @@\n-x\n+y\n"))
        assertNull("mode", HunkPatch.parse("diff --git a/s b/s\nold mode 100644\nnew mode 100755\n--- a/s\n+++ b/s\n@@ -1 +1 @@\n-x\n+y\n"))
        assertNull("binary", HunkPatch.parse("diff --git a/i b/i\nindex 1..2 100644\nBinary files a/i and b/i differ\n"))
        assertNull("untracked listing", HunkPatch.parse("@@ new file: x @@\n+a\n"))
        assertNull("two files", HunkPatch.parse(twoHunks + header + "@@ -1 +1 @@\n-a\n+b\n"))
        assertNull("counts don't add up", HunkPatch.parse(header + "@@ -1,3 +1,3 @@\n a\n-b\n"))
        assertNull("cut short", HunkPatch.parse(twoHunks + "\n${Git.DIFF_TRUNCATED} — 3 KB more.\n"))
        assertNull("empty", HunkPatch.parse(""))
    }

    @Test fun `hunk count matches what the diff view shows`() {
        val shown = ParsedDiff.parse(twoHunks).lines.count { it.kind == DiffLine.Kind.Hunk }
        assertEquals(shown, HunkPatch.parse(twoHunks)!!.hunks.size)
    }

    // ---- With a real git, when the machine running the tests has one --------------------------

    private fun git(dir: File, vararg args: String, input: String? = null): String {
        val p = ProcessBuilder(listOf("git", "-C", dir.path) + args).redirectErrorStream(true).start()
        if (input != null) p.outputStream.use { it.write(input.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out
    }

    private fun gitAvailable() = runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    private fun repo(content: String): File {
        val dir = Files.createTempDirectory("hunk").toFile()
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "t@example.com")
        git(dir, "config", "user.name", "T")
        git(dir, "config", "core.autocrlf", "false")
        git(dir, "config", "commit.gpgsign", "false")
        File(dir, "f.txt").writeText(content)
        git(dir, "add", "f.txt")
        git(dir, "commit", "-q", "-m", "init")
        return dir
    }

    @Test fun `staging and unstaging one hunk with git apply`() {
        assumeTrue(gitAvailable())
        val lines = (1..30).map { "line $it" }
        val dir = repo(lines.joinToString("\n", postfix = "\n"))
        try {
            File(dir, "f.txt").writeText(lines.toMutableList().apply { this[1] = "LINE 2"; this[25] = "LINE 26" }.joinToString("\n", postfix = "\n"))
            val unstaged = git(dir, *Git.diffArgs("f.txt", DiffSide.Unstaged).toTypedArray())
            assertEquals(2, HunkPatch.parse(unstaged)!!.hunks.size)
            // Stage only the second hunk.
            git(dir, "apply", "--cached", "--whitespace=nowarn", "-", input = HunkPatch.forHunk(unstaged, 1)!!)
            val staged = git(dir, *Git.diffArgs("f.txt", DiffSide.Staged).toTypedArray())
            assertTrue(staged.contains("+LINE 26") && !staged.contains("+LINE 2\n"))
            assertEquals(1, HunkPatch.parse(git(dir, *Git.diffArgs("f.txt", DiffSide.Unstaged).toTypedArray()))!!.hunks.size)
            // And unstage it again, from the staged diff.
            git(dir, "apply", "--cached", "--whitespace=nowarn", "--reverse", "-", input = HunkPatch.forHunk(staged, 0)!!)
            assertEquals("", git(dir, "diff", "--cached"))
            assertEquals("LINE 26", File(dir, "f.txt").readLines()[25]) // the work tree is untouched
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `CRLF and a missing final newline survive staging`() {
        assumeTrue(gitAvailable())
        val dir = repo("a\r\nb\r\nc")
        try {
            File(dir, "f.txt").writeText("a\r\nB\r\nc\r\nd")
            val unstaged = git(dir, *Git.diffArgs("f.txt", DiffSide.Unstaged).toTypedArray())
            val patch = HunkPatch.forHunk(unstaged, 0)!!
            assertTrue(patch.contains("\\ No newline at end of file"))
            git(dir, "apply", "--cached", "--whitespace=nowarn", "-", input = patch)
            assertEquals("", git(dir, "diff")) // everything is staged, byte for byte
        } finally {
            dir.deleteRecursively()
        }
    }
}
