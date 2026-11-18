package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRequestParseTest {
    private val listJson = """
        [
          {
            "additions": 120,
            "author": {"id": "MDQ6VXNlcjE=", "is_bot": false, "login": "octocat", "name": "The Octocat"},
            "baseRefName": "main",
            "deletions": 14,
            "headRefName": "feature/login",
            "isDraft": false,
            "number": 42,
            "reviewDecision": "CHANGES_REQUESTED",
            "statusCheckRollup": [
              {"__typename": "CheckRun", "completedAt": "2024-05-01T10:00:00Z", "conclusion": "SUCCESS", "detailsUrl": "https://github.com/o/r/actions/runs/1/job/2", "name": "build", "startedAt": "2024-05-01T09:55:00Z", "status": "COMPLETED", "workflowName": "CI"},
              {"__typename": "CheckRun", "conclusion": "FAILURE", "detailsUrl": "https://github.com/o/r/actions/runs/1/job/3", "name": "test (ubuntu-latest)", "status": "COMPLETED", "workflowName": "CI"},
              {"__typename": "CheckRun", "conclusion": "", "name": "lint", "status": "IN_PROGRESS", "workflowName": "CI"},
              {"__typename": "StatusContext", "context": "ci/circleci", "state": "ERROR", "targetUrl": "https://circleci.com/x"},
              {"__typename": "StatusContext", "context": "netlify/deploy", "state": "PENDING", "targetUrl": ""},
              {"__typename": "CheckRun", "conclusion": "SKIPPED", "name": "deploy", "status": "COMPLETED", "workflowName": "CD"}
            ],
            "title": "Add login screen",
            "updatedAt": "2024-05-01T10:20:30Z",
            "url": "https://github.com/o/r/pull/42"
          },
          {
            "additions": 1, "author": {"login": "app/dependabot", "is_bot": true}, "baseRefName": "main", "deletions": 1,
            "headRefName": "dependabot/npm/lodash-4.17.21", "isDraft": true, "number": 7, "reviewDecision": "",
            "statusCheckRollup": [], "title": "Bump lodash", "updatedAt": "2024-04-30T08:00:00.123+02:00"
          },
          {"title": "no number, skipped"}
        ]
    """.trimIndent()

    @Test
    fun parsesPrList() {
        val list = PrParse.list(listJson)!!
        assertEquals(2, list.size)
        val pr = list[0]
        assertEquals(42, pr.number)
        assertEquals("octocat", pr.author)
        assertEquals("feature/login", pr.headRef)
        assertEquals("CHANGES_REQUESTED", pr.reviewDecision)
        assertEquals(120, pr.additions)
        assertEquals(1714558830000L, pr.updatedAt)
        assertEquals("https://github.com/o/r/pull/42", pr.url)
        val bot = list[1]
        assertTrue(bot.isDraft)
        assertNull(bot.reviewDecision)
        assertEquals("app/dependabot", bot.author)
        assertEquals(PrParse.time("2024-04-30T06:00:00Z"), bot.updatedAt)
        assertNull(bot.checks.overall)
        assertEquals("No checks", bot.checks.label)
    }

    @Test
    fun summarisesChecks() {
        val checks = PrParse.list(listJson)!![0].checks
        assertEquals(PrCheck.State.Failed, checks.overall)
        assertEquals(listOf("test (ubuntu-latest)", "ci/circleci"), checks.failed.map { it.name })
        assertEquals("CI", checks.failed[0].workflow)
        assertEquals(2, checks.pending)
        assertEquals(1, checks.passed)
        assertEquals(1, checks.skipped)
        assertEquals("2 failing · 2 running · 1 passed · 1 skipped", checks.label)
        // An empty target URL is no link.
        assertNull(checks.checks.first { it.name == "netlify/deploy" }.url)
    }

    @Test
    fun checkStatesWithoutTypename() {
        val pending = CheckSummary(listOf(PrCheck("a", PrCheck.State.Passed), PrCheck("b", PrCheck.State.Pending)))
        assertEquals(PrCheck.State.Pending, pending.overall)
        val passed = CheckSummary(listOf(PrCheck("a", PrCheck.State.Passed), PrCheck("b", PrCheck.State.Skipped)))
        assertEquals(PrCheck.State.Passed, passed.overall)
        val timedOut = PrParse.list("""[{"number":1,"statusCheckRollup":[{"name":"x","status":"COMPLETED","conclusion":"TIMED_OUT"}]}]""")!!
        assertEquals(PrCheck.State.Failed, timedOut[0].checks.overall)
    }

    @Test
    fun rejectsNonJson() {
        assertNull(PrParse.list("no pull requests match your search"))
        assertNull(PrParse.detail("GraphQL: Could not resolve to a PullRequest"))
        assertEquals(emptyList<PrSummary>(), PrParse.list("[]"))
    }

    private val viewJson = """
        {
          "additions": 30, "deletions": 5,
          "author": {"login": "octocat"},
          "baseRefName": "main", "headRefName": "fix/crash",
          "body": "Fixes #12.\n\n## Changes\n- guard null",
          "comments": [
            {"author": {"login": "alice"}, "authorAssociation": "MEMBER", "body": "Thanks! One question.", "createdAt": "2024-05-02T09:00:00Z", "id": "IC_1", "url": "https://github.com/o/r/pull/9#issuecomment-1"},
            {"author": {"login": "octocat"}, "body": "Answered.", "createdAt": "2024-05-02T11:00:00Z"}
          ],
          "files": [
            {"path": "app/src/Main.kt", "additions": 25, "deletions": 5},
            {"path": "README.md", "additions": 5, "deletions": 0}
          ],
          "isDraft": false,
          "mergeable": "CONFLICTING",
          "number": 9,
          "reviewDecision": "APPROVED",
          "reviews": [
            {"author": {"login": "bob"}, "body": "", "state": "APPROVED", "submittedAt": "2024-05-02T10:00:00Z"},
            {"author": {"login": "carol"}, "body": "", "state": "COMMENTED", "submittedAt": "2024-05-02T10:30:00Z"}
          ],
          "state": "OPEN",
          "statusCheckRollup": [],
          "title": "Fix crash on start",
          "url": "https://github.com/o/r/pull/9"
        }
    """.trimIndent()

    @Test
    fun parsesPrDetail() {
        val pr = PrParse.detail(viewJson)!!
        assertEquals(9, pr.number)
        assertTrue(pr.isOpen)
        assertEquals("CONFLICTING", pr.mergeable)
        assertEquals(listOf("app/src/Main.kt", "README.md"), pr.files.map { it.path })
        // Comments and the approval, by time; carol's empty COMMENTED review only held inline comments.
        assertEquals(listOf("alice", "bob", "octocat"), pr.conversation.map { it.author })
        assertEquals("APPROVED", pr.conversation[1].reviewState)
        assertNull(pr.conversation[0].reviewState)
        assertTrue(pr.body.startsWith("Fixes #12."))
    }

    @Test
    fun parsesInlineReviewComments() {
        val json = """
            [
              {"id": 101, "path": "src/a.kt", "line": 12, "original_line": 10, "body": "Null here?", "user": {"login": "bob", "type": "User"},
               "created_at": "2024-05-02T10:00:00Z", "diff_hunk": "@@ -1,3 +1,4 @@"},
              {"id": 102, "path": "src/a.kt", "line": null, "original_line": 8, "body": "Outdated note", "user": {"login": "carol"},
               "created_at": "2024-05-02T09:00:00Z"},
              {"id": 103, "path": "src/a.kt", "line": 12, "body": "Fixed.", "user": null, "created_at": "2024-05-02T12:00:00Z", "in_reply_to_id": 101}
            ]
        """.trimIndent()
        val comments = PrParse.reviewComments(json)!!
        assertEquals(listOf(102L, 101L, 103L).size, comments.size)
        assertEquals("carol", comments[0].author)
        assertEquals(8, comments[0].line) // outdated: falls back to the original line
        assertEquals(12, comments[1].line)
        assertEquals("ghost", comments[2].author) // deleted account
        assertEquals(101L, comments[2].inReplyTo)
        assertNull(PrParse.reviewComments("""{"message":"Not Found"}"""))
    }

    private val diff = """
        diff --git a/src/app.kt b/src/app.kt
        index 83db48f..bf269f4 100644
        --- a/src/app.kt
        +++ b/src/app.kt
        @@ -1,4 +1,5 @@
         fun main() {
        -    println("hi")
        +    println("hello")
        +    // diff --git a/fake b/fake
        +++ counter
         }
        diff --git a/docs/old name.md b/docs/new name.md
        similarity index 90%
        rename from docs/old name.md
        rename to docs/new name.md
        index 1111111..2222222 100644
        --- a/docs/old name.md
        +++ b/docs/new name.md
        @@ -1 +1 @@
        -Old
        +New
        diff --git a/gone.txt b/gone.txt
        deleted file mode 100644
        index e69de29..0000000
        --- a/gone.txt
        +++ /dev/null
        @@ -1,2 +0,0 @@
        -a
        --- b
        diff --git a/img/logo.png b/img/logo.png
        new file mode 100644
        index 0000000..3b18e51
        Binary files /dev/null and b/img/logo.png differ
        diff --git "a/caf\303\251.txt" "b/caf\303\251.txt"
        new file mode 100644
        index 0000000..e69de29
        diff --git a/empty-renamed b/empty-moved
        similarity index 100%
        rename from empty-renamed
        rename to empty-moved
    """.trimIndent() + "\n"

    @Test
    fun splitsDiffPerFile() {
        val files = PrParse.splitDiff(diff)
        assertEquals(listOf("src/app.kt", "docs/new name.md", "gone.txt", "img/logo.png", "café.txt", "empty-moved"), files.map { it.path })

        val app = files[0]
        assertEquals(3, app.added) // includes the added lines that look like headers
        assertEquals(1, app.removed)
        assertFalse(app.binary)
        assertTrue(app.text.startsWith("diff --git a/src/app.kt"))
        assertTrue(app.text.endsWith("+++ counter\n }"))

        assertEquals("docs/old name.md", files[1].oldPath)
        assertEquals(2, files[2].removed) // "--- b" inside the hunk is a removed line
        assertNull(files[2].oldPath)
        assertTrue(files[3].binary)
        assertEquals("empty-renamed", files[5].oldPath)
        assertEquals(0, files[5].added)
    }

    @Test
    fun splitDiffOfNothing() {
        assertTrue(PrParse.splitDiff("").isEmpty())
        assertTrue(PrParse.splitDiff("warning: something\n").isEmpty())
    }

    @Test
    fun parsesTimes() {
        assertEquals(1714558830000L, PrParse.time("2024-05-01T10:20:30Z"))
        assertEquals(1714558830000L, PrParse.time("2024-05-01T12:20:30.5+02:00"))
        assertEquals(0L, PrParse.time(null))
        assertEquals(0L, PrParse.time("yesterday"))
    }
}

class PrPromptsTest {
    private fun pr(failing: Boolean, body: String = "Body text") = PrDetail(
        number = 5, title = "Speed up search", author = "dana", headRef = "perf/search", baseRef = "main", isDraft = false,
        state = "OPEN", body = body, url = "https://github.com/o/r/pull/5", reviewDecision = null,
        checks = CheckSummary(if (failing) listOf(PrCheck("unit tests", PrCheck.State.Failed, "CI"), PrCheck("lint", PrCheck.State.Passed)) else emptyList()),
        conversation = emptyList(), files = listOf(PrFile("src/Search.kt", 40, 12)), additions = 40, deletions = 12, mergeable = "MERGEABLE",
    )

    @Test
    fun reviewPromptHasContextAndLimits() {
        val p = PrPrompts.review(pr(false))
        assertTrue(p.contains("#5"))
        assertTrue(p.contains("Speed up search"))
        assertTrue(p.contains("gh pr diff 5"))
        assertTrue(p.contains("src/Search.kt (+40 -12)"))
        assertTrue(p.contains("don't push"))
        val long = PrPrompts.review(pr(false, body = "x".repeat(5000)))
        assertTrue(long.contains("cut short"))
        assertTrue(long.length < 3000)
    }

    @Test
    fun fixChecksPromptNamesFailuresAndBranch() {
        val other = PrPrompts.fixChecks(pr(true), currentBranch = "main")
        assertTrue(other.contains("- unit tests (workflow: CI)"))
        assertFalse(other.contains("- lint"))
        assertTrue(other.contains("gh pr checkout 5"))
        val same = PrPrompts.fixChecks(pr(true), currentBranch = "perf/search")
        assertFalse(same.contains("gh pr checkout"))
        assertTrue(same.contains("don't push"))
    }
}
