package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhReposTest {
    @Test
    fun `github slug from https, ssh and git suffix`() {
        assertEquals("octo/app", Git.githubSlug("https://github.com/Octo/App.git"))
        assertEquals("octo/app", Git.githubSlug("git@github.com:octo/app.git"))
        assertEquals("octo/app", Git.githubSlug("https://github.com/octo/app/"))
        assertEquals("octo/my.lib", Git.githubSlug("https://github.com/octo/my.lib"))
        assertNull(Git.githubSlug("https://gitlab.com/octo/app.git"))
    }

    @Test
    fun `parses gh repo list json`() {
        val out = """
            [{"description":"Phone agents","isFork":false,"isPrivate":true,"name":"yurlungr","nameWithOwner":"octo/yurlungr","pushedAt":"2026-11-01T10:00:00Z"},
             {"description":"","isFork":true,"isPrivate":false,"name":"fork","nameWithOwner":"octo/fork","pushedAt":null}]
        """.trimIndent()
        val repos = Git.parseRepos(out)!!
        assertEquals(2, repos.size)
        assertEquals("octo/yurlungr", repos[0].slug)
        assertTrue(repos[0].isPrivate)
        assertEquals("Phone agents", repos[0].description)
        assertTrue(repos[0].updatedAt > 0)
        assertNull(repos[1].description)
        assertTrue(repos[1].isFork)
        assertFalse(repos[1].isPrivate)
        assertEquals(0L, repos[1].updatedAt)
    }

    @Test
    fun `non-json output is null`() {
        assertNull(Git.parseRepos("error: not logged in"))
    }
}

class BranchParseTest {
    private fun line(vararg f: String) = f.joinToString("\u001f")

    @Test
    fun `locals first, remotes only when not checked out`() {
        val out = listOf(
            line("refs/heads/main", "main", "origin/main", "2 hours ago", "*"),
            line("refs/heads/feature", "feature", "", "1 day ago", " "),
            line("refs/remotes/origin/HEAD", "origin", "", "2 hours ago", " "),
            line("refs/remotes/origin/main", "origin/main", "", "2 hours ago", " "),
            line("refs/remotes/origin/feature", "origin/feature", "", "1 day ago", " "),
            line("refs/remotes/origin/fix", "origin/fix", "", "3 days ago", " "),
        ).joinToString("\n")
        val b = Git.parseBranches(out)
        assertEquals(listOf("main", "feature", "origin/fix"), b.map { it.name })
        assertTrue(b[0].isCurrent)
        assertTrue(b[2].isRemote)
        assertEquals("origin/main", b[0].upstream)
    }
}
