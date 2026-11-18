package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeVersionsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(path: String, text: String) = File(tmp.root, path).apply { parentFile?.mkdirs(); writeText(text) }
    private fun ok(range: String, version: String) = assertEquals("$range ∋ $version", true, VersionRange.satisfies(range, version))
    private fun no(range: String, version: String) = assertEquals("$range ∌ $version", false, VersionRange.satisfies(range, version))
    private fun unknown(range: String, version: String = "1.2.3") = assertNull(range, VersionRange.satisfies(range, version))

    @Test fun `versions are read from tool output`() {
        assertEquals(listOf(22, 11, 0), VersionRange.parseVersion("v22.11.0")!!.toList())
        assertEquals(listOf(3, 12, 7), VersionRange.parseVersion("Python 3.12.7")!!.toList())
        assertEquals(listOf(1, 23, 2), VersionRange.parseVersion("go version go1.23.2 android/arm64")!!.toList())
        assertEquals(listOf(8, 3, 0), VersionRange.parseVersion("PHP 8.3 (cli)")!!.toList())
        assertNull(VersionRange.parseVersion("command not found"))
    }

    @Test fun `exact, partial and x-ranges`() {
        ok("18.17.0", "v18.17.0"); no("18.17.0", "18.17.1")
        ok("18", "18.20.4"); no("18", "22.11.0")
        ok("18.x", "18.0.0"); no("18.x", "19.0.0")
        ok("3.11.*", "3.11.9"); no("3.11.*", "3.12.0")
        ok("==3.11.*", "3.11.2"); ok("*", "99.0.0"); ok("x", "1.0.0")
        ok("v20", "20.1.0"); ok("=20.1.0", "20.1.0")
    }

    @Test fun `caret and tilde`() {
        ok("^18.2.0", "18.20.0"); no("^18.2.0", "18.1.9"); no("^18.2.0", "19.0.0")
        ok("^0.2.3", "0.2.9"); no("^0.2.3", "0.3.0")
        ok("^0.0.3", "0.0.3"); no("^0.0.3", "0.0.4")
        ok("^8.1", "8.3.12"); no("^8.1", "9.0.0")
        ok("~1.2.3", "1.2.9"); no("~1.2.3", "1.3.0")
        ok("~1.2", "1.2.0"); no("~1.2", "1.3.0"); ok("~1", "1.9.9")
    }

    @Test fun `comparisons and their combinations`() {
        ok(">=18", "22.11.0"); no(">=18", "16.20.0")
        ok(">=18.0.0 <21", "20.9.0"); no(">=18.0.0 <21", "21.0.0"); no(">= 18 < 21", "22.0.0")
        ok(">=3.9,<3.13", "3.12.7"); no(">=3.9,<3.13", "3.13.0"); no(">=3.9, <3.12", "3.12.1")
        ok(">18", "19.0.0"); no(">18", "18.9.0"); ok(">18.1.0", "18.1.1"); no(">18.1.0", "18.1.0")
        ok("<=18", "18.9.9"); no("<=18", "19.0.0"); ok("<=3.11.4", "3.11.4"); no("<=3.11.4", "3.11.5")
        ok("!=3.9.*", "3.10.0"); no("!=3.9.*", "3.9.1")
        ok("1.2.3 - 2.3", "2.3.9"); no("1.2.3 - 2.3", "2.4.0"); no("1.2.3 - 2.3.4", "2.3.5")
    }

    @Test fun `or-ranges and python compatible release`() {
        ok("^18.18.0 || ^20.9.0 || >=21.1.0", "22.11.0"); no("^16 || ^18", "22.11.0"); ok("^16 || ^18", "18.1.0")
        ok("~=3.10", "3.12.0"); no("~=3.10", "4.0.0"); no("~=3.10", "3.9.0")
        ok("~=3.10.2", "3.10.9"); no("~=3.10.2", "3.11.0")
        ok("18.0.0-rc.1", "18.0.0")
    }

    @Test fun `unknown syntax gives no answer`() {
        unknown("lts/*"); unknown("node"); unknown("latest"); unknown("~=3"); unknown(">=18 || lts/hydrogen"); unknown("")
        assertNull(VersionRange.satisfies(">=18", "unknown"))
    }

    @Test fun `version files and manifests are read`() {
        write(".nvmrc", "v18.17.0\n")
        write(".python-version", "3.11.4\n3.10.0\n")
        write("package.json", """{"name":"x","engines":{"node":">=18 <21","npm":">=9"}}""")
        write("pyproject.toml", "[project]\nname = 'x'\nrequires-python = \">=3.9,<3.12\"\n")
        write("go.mod", "module example.com/x\n\ngo 1.22.1\n\ntoolchain go1.22.4\n")
        write("rust-toolchain.toml", "[toolchain]\nchannel = \"1.80.0\"\n")
        write("composer.json", """{"require":{"php":"^7.4|^8.0","ext-json":"*"}}""")
        val r = RuntimeRequirements.read(tmp.root).associate { it.source to it.range }
        assertEquals("18", r[".nvmrc"])
        assertEquals("3.11", r[".python-version"])
        assertEquals(">=18 <21", r["package.json engines.node"])
        assertEquals(">=3.9,<3.12", r["pyproject.toml requires-python"])
        assertEquals(">=1.22.1", r["go.mod"])
        assertEquals(">=1.80.0", r["rust-toolchain.toml"])
        assertEquals("^7.4||^8.0", r["composer.json require.php"])
    }

    @Test fun `tool-versions, poetry, legacy rust-toolchain and aliases`() {
        write(".tool-versions", "nodejs 20.11.1\npython 3.12.2 3.11.0\ngolang 1.21.0\n# ruby 3.3.0\nterraform 1.7.0\n")
        write(".nvmrc", "lts/iron\n")
        write("pyproject.toml", "[tool.poetry]\nname = 'x'\n\n[tool.poetry.dependencies]\npython = \"^3.10\"\nrequests = \"^2\"\n\n[build-system]\n")
        write("rust-toolchain", "nightly-2024-05-01\n")
        val reqs = RuntimeRequirements.read(tmp.root)
        val bySource = reqs.groupBy({ it.source }, { it.runtime to it.range })
        assertEquals(listOf("node" to "20", "python" to "3.12", "go" to ">=1.21.0"), bySource[".tool-versions"])
        assertNull(bySource[".nvmrc"])
        assertEquals(listOf("python" to "^3.10"), bySource["pyproject.toml (Poetry)"])
        assertEquals(listOf("rust" to "nightly"), bySource["rust-toolchain"])
    }

    @Test fun `mismatches warn only on clear differences`() {
        val reqs = listOf(
            RuntimeRequirement("node", "18", ".nvmrc", "18.17.0"),
            RuntimeRequirement("python", ">=3.9", "pyproject.toml requires-python", ">=3.9"),
            RuntimeRequirement("go", ">=1.24", "go.mod", "1.24"),
            RuntimeRequirement("php", "lts/*", "x", "lts/*"),
            RuntimeRequirement("rust", "nightly", "rust-toolchain", "nightly"),
        )
        val installed = mapOf("node" to "v22.11.0", "python" to "Python 3.12.7", "go" to null, "php" to "PHP 8.3.12 (cli)", "rust" to "rustc 1.82.0 (f6e511eec 2024-10-15)")
        val warnings = RuntimeRequirements.mismatches(reqs, installed)
        assertEquals(listOf("node", "rust"), warnings.map { it.requirement.runtime })
        assertEquals("Wants Node.js 18.17.0, this phone has 22.11.0", warnings[0].title)
        assertTrue(RuntimeRequirements.advice(warnings[1]).contains("stable Rust"))
        assertTrue(RuntimeRequirements.mismatches(reqs.take(1), mapOf("node" to "v18.20.4")).isEmpty())
    }
}
