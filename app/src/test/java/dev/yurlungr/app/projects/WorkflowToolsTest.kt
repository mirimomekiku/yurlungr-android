package dev.yurlungr.app.projects

import dev.yurlungr.app.editor.EditorTools
import dev.yurlungr.app.ui.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkflowToolsTest {
    private val root = File("/p")

    @Test fun `problems from Kotlin, tsc, eslint, javac, rustc, go, python and dotnet`() {
        val log = """
            e: file:///p/app/src/Main.kt:12:5 Unresolved reference 'foo'.
            w: file:///p/app/src/Main.kt:20:1 Parameter 'x' is never used
            src/app.ts(3,7): error TS2322: Type 'string' is not assignable to type 'number'.
            /p/web/src/a.js:4:10: 'x' is defined but never used. [Error/no-unused-vars]
            /p/web/src/b.js:1:1: Unexpected console statement. [Warning/no-console]
            Hello.java:7: error: ';' expected
            src/main.rs:9:5: error[E0308]: mismatched types
            ./cmd/main.go:15:2: fmt.Printf format %d has arg x of wrong type string
              File "app/views.py", line 3
                def broken(
            SyntaxError: '(' was never closed
            Program.cs(10,13): error CS1002: ; expected [/p/App.csproj]
            BUILD SUCCESSFUL in 3s
            > Task :app:compileDebugKotlin
        """.trimIndent()
        val problems = ProjectChecks.parseProblems(log, root)
        fun at(file: String, line: Int) = problems.single { it.file == file && it.line == line }
        assertEquals(Severity.Error, at("app/src/Main.kt", 12).severity)
        assertEquals(Severity.Warning, at("app/src/Main.kt", 20).severity)
        assertEquals(7, at("src/app.ts", 3).col)
        assertEquals(Severity.Error, at("web/src/a.js", 4).severity)
        assertEquals(Severity.Warning, at("web/src/b.js", 1).severity)
        assertEquals("';' expected", at("Hello.java", 7).message)
        assertTrue(at("src/main.rs", 9).message.contains("mismatched"))
        assertEquals("cmd/main.go", at("cmd/main.go", 15).file)
        assertTrue(at("app/views.py", 3).message.startsWith("SyntaxError"))
        assertEquals("; expected", at("Program.cs", 10).message)
        assertEquals(10, problems.size)
    }

    @Test fun `junit, jest, go and cargo results, with failure locations`() {
        val junit = """<testsuites><testsuite name="s"><testcase classname="calc.AddTest" name="adds">
            </testcase><testcase classname="calc.AddTest" name="carries"><failure message="expected 2 but was 3">at calc.AddTest.carries(AddTest.kt:21)</failure>
            </testcase><testcase classname="x" name="later"><skipped/></testcase></testsuite></testsuites>"""
        val cases = ProjectChecks.parseJunit(junit)
        assertEquals(listOf(TestStatus.Passed, TestStatus.Failed, TestStatus.Skipped), cases.map { it.status })
        assertEquals("AddTest.kt" to 21, cases[1].file to cases[1].line)
        assertEquals("expected 2 but was 3", cases[1].message)

        val jest = """{"testResults":[{"name":"/p/src/sum.test.js","assertionResults":[
            {"fullName":"sum adds","status":"passed","failureMessages":[]},
            {"fullName":"sum fails","status":"failed","failureMessages":["Error: expected 3\n    at Object.<anonymous> (src/sum.test.js:8:17)"]}]}]}"""
        val j = ProjectChecks.parseJest(jest)
        assertEquals(TestStatus.Failed, j[1].status)
        assertEquals("src/sum.test.js" to 8, j[1].file to j[1].line)

        val text = """
            === RUN   TestAdd
            --- PASS: TestAdd (0.00s)
            === RUN   TestSub
                calc_test.go:14: got 1, want 2
            --- FAIL: TestSub (0.00s)
            test tests::works ... ok
            test tests::breaks ... FAILED
            ---- tests::breaks stdout ----
            thread 'tests::breaks' panicked at src/lib.rs:10:9:
        """.trimIndent()
        val t = ProjectChecks.parseTextTests(text)
        assertEquals(listOf("TestAdd", "TestSub", "tests::works", "tests::breaks"), t.map { it.name })
        assertEquals("calc_test.go" to 14, t[1].file to t[1].line)
        assertEquals("src/lib.rs" to 10, t[3].file to t[3].line)
    }

    @Test fun `check commands run in their folders and keep their output`() {
        // File paths as the platform writes them (Android: /; the test may run elsewhere).
        val log = File("/p/.yurlungr/problems.log")
        val cmd = ProjectChecks.terminalCommand(root, listOf(CheckCommand(File("/p/web app"), "npx tsc --noEmit")), log, clearResults = true)
        assertTrue(cmd.contains("(cd '${File("/p/web app").path}' && npx tsc --noEmit)"))
        assertTrue(cmd.endsWith("| tee '${log.path}'"))
        assertTrue("results are git-ignored", cmd.contains("printf '*\\n' > '${File(File("/p"), ".yurlungr/.gitignore").path}'"))
    }

    @Test fun `editor word, definitions and blame`() {
        assertEquals("fooBar", EditorTools.wordAt("x = fooBar()", 6))
        assertEquals("fooBar", EditorTools.wordAt("x = fooBar()", 10))
        assertNull(EditorTools.wordAt("a + 1", 2))
        val def = Regex(EditorTools.definitionPattern("render"))
        assertTrue(def.containsMatchIn("fun render(x: Int)"))
        assertTrue(def.containsMatchIn("export function render() {"))
        assertTrue(def.containsMatchIn("const render = (props) => null"))
        assertTrue(def.containsMatchIn("def render(self):"))
        assertTrue(!def.containsMatchIn("render(x)") && !def.containsMatchIn("fun renderAll()"))
        val porcelain = """
            ${"a".repeat(40)} 1 1 2
            author Ada
            author-time 1700000000
            summary First
            	line one
            ${"a".repeat(40)} 2 2
            	line two
            ${"0".repeat(40)} 3 3 1
            author Not Committed Yet
            author-time 1700000100
            summary Version of x
            	line three
        """.trimIndent()
        val blame = EditorTools.parseBlame(porcelain)
        assertEquals(2, blame.size)
        assertEquals(1 to 2, blame[0].first to blame[0].last)
        assertEquals("Ada", blame[0].author)
        assertTrue(blame[1].uncommitted)
    }

    @Test fun `palette ranks exact and word-start matches above scattered ones`() {
        assertTrue(Palette.score("set", "Settings") > Palette.score("set", "Reset"))
        assertTrue(Palette.score("prjscr", "ProjectScreen.kt") > 0)
        assertEquals(0, Palette.score("xyz", "Settings"))
        assertTrue(Palette.score("dev env", "Development environment") == 0 || Palette.score("dev", "Development environment") > 0)
    }
}
