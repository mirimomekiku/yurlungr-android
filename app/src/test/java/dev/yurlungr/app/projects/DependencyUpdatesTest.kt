package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DependencyUpdatesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun stack(id: String, name: String, dir: File = File("/p")) = DetectedStack(id, name, dir, "x")

    // ---- Versions ----

    @Test fun `bumps follow semver, 0x minors count as major`() {
        assertEquals(Bump.Patch, DepVersions.bump("1.2.3", "1.2.4"))
        assertEquals(Bump.Minor, DepVersions.bump("1.2.3", "1.5.0"))
        assertEquals(Bump.Major, DepVersions.bump("1.2.3", "2.0.0"))
        assertEquals(Bump.Major, DepVersions.bump("0.2.3", "0.3.0"))
        assertEquals(Bump.Patch, DepVersions.bump("0.2.3", "0.2.9"))
        // Go pseudo-versions: same numbers, nothing to call safe.
        assertEquals(Bump.Unknown, DepVersions.bump("v0.0.0-20210101-abc", "v0.0.0-20220101-def"))
        assertEquals(Bump.Patch, DepVersions.bump("1.0.0-beta.1", "1.0.0"))
        assertEquals(Bump.Unknown, DepVersions.bump("2.0.0", "1.9.0"))
        assertEquals(Bump.Unknown, DepVersions.bump(null, "1.0.0"))
        assertEquals(Bump.Unknown, DepVersions.bump("---", "1.0.0"))
        assertEquals(Bump.Patch, DepVersions.bump("v0.14.0", "v0.14.1"))
        assertEquals(Bump.Major, DepVersions.bump("2023.7.22", "2024.2.2"))
    }

    @Test fun `safe target prefers latest, falls back to wanted when latest is major`() {
        assertEquals("18.3.1", OutdatedDep("react", "18.2.0", "18.3.1", "18.3.1").safeTarget)
        assertEquals("4.17.21", OutdatedDep("lodash", "4.17.0", "4.17.21", "5.0.0").safeTarget)
        assertNull(OutdatedDep("eslint", "8.57.0", "8.57.0", "9.0.0").safeTarget)
        assertNull(OutdatedDep("missing", null, "1.0.0", "1.1.0").safeTarget)
        assertFalse(OutdatedDep("golang.org/x/text", "v0.3.0", null, "v0.3.7", "indirect").autoSafe)
        assertTrue(OutdatedDep("golang.org/x/text", "v0.3.0", null, "v0.3.7").autoSafe)
    }

    // ---- Tools ----

    @Test fun `tools come from the detected stacks`() {
        val tools = DependencyUpdates.toolsFor(listOf(
            stack("node", "Node.js (pnpm)"), stack("python", "Python (uv)"), stack("python", "Python", File("/q")),
            stack("go", "Go"), stack("rust", "Rust (Cargo)"), stack("ruby", "Ruby (Bundler)"),
        )).map { it.first }
        assertEquals(listOf(DepTool.Pnpm, DepTool.Uv, DepTool.Pip, DepTool.Go, DepTool.Cargo), tools)
        assertEquals(DepTool.Npm, DependencyUpdates.toolsFor(listOf(stack("node", "Node.js (npm)"))).single().first)
    }

    @Test fun `yarn berry is told apart`() {
        val dir = temp.newFolder("y")
        File(dir, "package.json").writeText("""{"name":"x","packageManager":"yarn@4.1.0"}""")
        assertTrue(DependencyUpdates.isYarnBerry(dir))
        File(dir, "package.json").writeText("""{"name":"x","packageManager":"yarn@1.22.19"}""")
        assertFalse(DependencyUpdates.isYarnBerry(dir))
        assertNotNull(DependencyUpdates.notReady(DepTool.Pip, dir))
    }

    // ---- npm ----

    @Test fun `npm outdated json`() {
        val out = """
            {
              "react": { "current": "18.2.0", "wanted": "18.3.1", "latest": "18.3.1", "dependent": "app", "location": "/p/node_modules/react" },
              "eslint": { "current": "8.57.0", "wanted": "8.57.1", "latest": "9.9.0", "dependent": "app", "type": "devDependencies" },
              "left-pad": { "wanted": "1.3.0", "latest": "1.3.0", "dependent": "app", "location": "" },
              "typescript": [
                { "current": "5.3.3", "wanted": "5.3.3", "latest": "5.5.4", "dependent": "web" },
                { "current": "5.3.3", "wanted": "5.3.3", "latest": "5.5.4", "dependent": "api" }
              ]
            }
        """.trimIndent()
        val deps = DependencyUpdates.parseNpm(out)!!
        assertEquals(listOf("react", "eslint", "left-pad", "typescript"), deps.map { it.name })
        assertEquals("dev", deps[1].kind)
        assertEquals(Bump.Major, deps[1].bump)
        assertEquals("8.57.1", deps[1].safeTarget)
        assertNull(deps[2].current)
        assertEquals(Bump.Minor, deps[3].bump)
        assertEquals(emptyList<OutdatedDep>(), DependencyUpdates.parseNpm("{}"))
        assertEquals(emptyList<OutdatedDep>(), DependencyUpdates.parseNpm(""))
    }

    @Test fun `npm error json is a failure with its summary`() {
        val out = """{"error":{"code":"ENOENT","summary":"Could not read package.json","detail":""}}"""
        assertNull(DependencyUpdates.parseNpm(out))
        assertEquals("Could not read package.json", DependencyUpdates.errorOf(out))
        assertNull(DependencyUpdates.parseNpm("npm ERR! something"))
    }

    // ---- pnpm ----

    @Test fun `pnpm outdated json, object and array forms`() {
        val obj = """
            {
              "vite": { "current": "5.0.0", "latest": "5.4.2", "wanted": "5.0.0", "isDeprecated": false, "dependencyType": "devDependencies" },
              "next": { "current": "13.5.6", "latest": "14.2.5", "wanted": "13.5.6", "isDeprecated": false, "dependencyType": "dependencies" }
            }
        """.trimIndent()
        val deps = DependencyUpdates.parsePnpm(obj)!!
        assertEquals(listOf("vite", "next"), deps.map { it.name })
        assertEquals("dev", deps[0].kind)
        assertEquals(Bump.Major, deps[1].bump)
        val arr = """[{"packageName":"zod","current":"3.22.0","latest":"3.23.8","wanted":"3.22.0","dependencyType":"dependencies"}]"""
        assertEquals("zod", DependencyUpdates.parsePnpm(arr)!!.single().name)
        assertEquals(emptyList<OutdatedDep>(), DependencyUpdates.parsePnpm("{}"))
    }

    // ---- Yarn 1 ----

    @Test fun `yarn classic json lines`() {
        val out = """
            {"type":"info","data":"Color legend : \n \"<red>\"    : Major Update backward-incompatible updates"}
            {"type":"table","data":{"head":["Package","Current","Wanted","Latest","Package Type","URL"],"body":[["chalk","4.1.0","4.1.2","5.3.0","dependencies","https://github.com/chalk/chalk"],["jest","29.0.0","29.7.0","29.7.0","devDependencies","https://jestjs.io/"]]}}
        """.trimIndent()
        val deps = DependencyUpdates.parseYarnClassic(out)!!
        assertEquals(listOf("chalk", "jest"), deps.map { it.name })
        assertEquals("4.1.2", deps[0].safeTarget)
        assertEquals("dev", deps[1].kind)
        assertEquals(emptyList<OutdatedDep>(), DependencyUpdates.parseYarnClassic("""{"type":"info","data":"All up to date"}"""))
        assertNull(DependencyUpdates.parseYarnClassic("not json at all"))
    }

    // ---- Bun ----

    @Test fun `bun outdated table`() {
        val out = """
            bun outdated v1.1.20 (ae194892)
            ┌──────────────────┬─────────┬────────┬────────┐
            │ Package          │ Current │ Update │ Latest │
            ├──────────────────┼─────────┼────────┼────────┤
            │ @types/bun (dev) │ 1.1.0   │ 1.1.6  │ 1.1.6  │
            ├──────────────────┼─────────┼────────┼────────┤
            │ elysia           │ 0.8.17  │ 0.8.17 │ 1.1.5  │
            └──────────────────┴─────────┴────────┴────────┘
        """.trimIndent()
        val deps = DependencyUpdates.parseBunTable(out)!!
        assertEquals(listOf("@types/bun", "elysia"), deps.map { it.name })
        assertEquals("dev", deps[0].kind)
        assertEquals("1.1.6", deps[0].safeTarget)
        assertEquals(Bump.Major, deps[1].bump)
        assertNull(deps[1].safeTarget)
        assertEquals(emptyList<OutdatedDep>(), DependencyUpdates.parseBunTable("bun outdated v1.1.20 (ae194892)\n"))
    }

    // ---- Python ----

    @Test fun `pip list outdated json, with a trailing notice`() {
        val out = """[{"name": "requests", "version": "2.25.1", "latest_version": "2.32.3", "latest_filetype": "wheel"}, {"name": "pip", "version": "23.0", "latest_version": "24.2", "latest_filetype": "wheel"}, {"name": "Django", "version": "4.2.0", "latest_version": "5.1", "latest_filetype": "wheel"}]
            |
            |[notice] A new release of pip is available: 23.0 -> 24.2
        """.trimMargin()
        val deps = DependencyUpdates.parse(DepTool.Pip, out)!!
        assertEquals(listOf("requests", "Django"), deps.map { it.name })
        assertEquals(Bump.Minor, deps[0].bump)
        assertEquals(Bump.Major, deps[1].bump)
        // uv's list keeps pip (it's what uv was asked about).
        assertEquals(3, DependencyUpdates.parse(DepTool.Uv, out)!!.size)
        assertEquals(emptyList<OutdatedDep>(), DependencyUpdates.parsePip("[]"))
        assertNull(DependencyUpdates.parsePip("ERROR: something"))
    }

    @Test fun `poetry show outdated text`() {
        val out = """
            Warning: poetry.lock is not consistent with pyproject.toml.
            fastapi     0.110.0 0.112.1 FastAPI framework, high performance
            httpx   (!) 0.26.0  0.27.0  The next generation HTTP client.
            pydantic    2.6.0   2.6.0   Data validation
        """.trimIndent()
        val deps = DependencyUpdates.parsePoetry(out)
        assertEquals(listOf("fastapi", "httpx"), deps.map { it.name })
        assertEquals("0.26.0", deps[1].current)
        assertEquals(Bump.Major, deps[0].bump)
    }

    // ---- Go ----

    @Test fun `go list -m -u -json all, concatenated objects`() {
        val out = """
            {
            	"Path": "example.com/app",
            	"Main": true,
            	"Dir": "/p",
            	"GoMod": "/p/go.mod",
            	"GoVersion": "1.22"
            }
            {
            	"Path": "github.com/gin-gonic/gin",
            	"Version": "v1.9.1",
            	"Update": {
            		"Path": "github.com/gin-gonic/gin",
            		"Version": "v1.10.0",
            		"Time": "2024-05-07T03:23:42Z"
            	},
            	"Time": "2023-06-01T00:00:00Z"
            }
            {
            	"Path": "golang.org/x/text",
            	"Version": "v0.14.0",
            	"Update": {
            		"Path": "golang.org/x/text",
            		"Version": "v0.17.0"
            	},
            	"Indirect": true
            }
            {
            	"Path": "github.com/stretchr/testify",
            	"Version": "v1.9.0",
            	"Error": { "Err": "a \"quoted\" { brace" }
            }
        """.trimIndent()
        val deps = DependencyUpdates.parseGoList(out)!!
        assertEquals(listOf("github.com/gin-gonic/gin", "golang.org/x/text"), deps.map { it.name })
        assertEquals(Bump.Minor, deps[0].bump)
        assertEquals("indirect", deps[1].kind)
        assertEquals(Bump.Major, deps[1].bump)
        assertEquals(4, DependencyUpdates.splitJsonObjects(out).size)
    }

    // ---- Cargo ----

    @Test fun `cargo outdated json, per crate`() {
        val out = """
            {"crate_name":"app","dependencies":[{"name":"serde","project":"1.0.190","compat":"1.0.208","latest":"1.0.208","kind":"Normal","platform":null},{"name":"clap","project":"3.2.25","compat":"3.2.25","latest":"4.5.16","kind":"Normal","platform":null},{"name":"tempfile","project":"3.8.0","compat":"3.12.0","latest":"3.12.0","kind":"Development","platform":null},{"name":"gone","project":"1.0.0","compat":"Removed","latest":"Removed","kind":"Normal","platform":null}]}
            {"crate_name":"lib","dependencies":[{"name":"rand","project":"0.8.5","compat":"---","latest":"0.9.0","kind":"Normal","platform":null}]}
        """.trimIndent()
        val deps = DependencyUpdates.parseCargoOutdated(out)!!
        assertEquals(listOf("serde", "clap", "tempfile", "rand"), deps.map { it.name })
        assertEquals("1.0.208", deps[0].safeTarget)
        assertNull(deps[1].safeTarget)
        assertEquals("dev", deps[2].kind)
        assertEquals(Bump.Major, deps[3].bump)
        assertNull(DependencyUpdates.parseCargoOutdated("error: no such command: `outdated`"))
    }

    // ---- Update commands ----

    @Test fun `update commands per tool, quoted, dev deps kept apart`() {
        fun u(name: String, version: String, kind: String? = null, wanted: String? = null) = DepUpdate(OutdatedDep(name, "1.0.0", wanted, version, kind), version)
        assertEquals("npm install 'react@18.3.1' && npm install --save-dev 'eslint@8.57.1'",
            DependencyUpdates.updateCommand(DepTool.Npm, listOf(u("react", "18.3.1"), u("eslint", "8.57.1", "dev"))))
        assertEquals("pnpm update '@types/node@20.14.0'", DependencyUpdates.updateCommand(DepTool.Pnpm, listOf(u("@types/node", "20.14.0"))))
        assertEquals("bun add --dev '@types/bun@1.1.6'", DependencyUpdates.updateCommand(DepTool.Bun, listOf(u("@types/bun", "1.1.6", "dev"))))
        assertEquals(".venv/bin/python -m pip install --upgrade 'requests==2.32.3'", DependencyUpdates.updateCommand(DepTool.Pip, listOf(u("requests", "2.32.3"))))
        assertEquals("uv lock --upgrade-package 'httpx' && uv sync", DependencyUpdates.updateCommand(DepTool.Uv, listOf(u("httpx", "0.27.0"))))
        assertEquals("go get 'github.com/gin-gonic/gin@v1.10.0' && go mod tidy", DependencyUpdates.updateCommand(DepTool.Go, listOf(u("github.com/gin-gonic/gin", "v1.10.0"))))
        assertEquals("cargo update -p 'serde' && cargo add --dev 'clap@4.5.16'",
            DependencyUpdates.updateCommand(DepTool.Cargo, listOf(u("serde", "1.0.208", wanted = "1.0.208"), u("clap", "4.5.16", "dev", wanted = "3.2.25"))))
        assertTrue(DependencyUpdates.updateCommand(DepTool.Poetry, listOf(u("fastapi", "0.112.1")))!!.endsWith("update 'fastapi'"))
    }

    @Test fun `odd names and versions are left out`() {
        val bad = listOf(
            DepUpdate(OutdatedDep("--registry=evil", "1", null, "2"), "2"),
            DepUpdate(OutdatedDep("x", "1", null, "2"), "2; rm -rf ~"),
            DepUpdate(OutdatedDep("a'b", "1", null, "2"), "2"),
        )
        assertNull(DependencyUpdates.updateCommand(DepTool.Npm, bad))
    }

    @Test fun `terminal command keeps the exit code and runs checks only after a good update`() {
        val root = File("/p")
        val web = File(root, "web")
        val cmd = DependencyUpdates.terminalCommand(root, listOf(web to "npm install 'a@1.0.1'"), "CHECKS")
        assertTrue(cmd.contains("(cd ${Git.shellQuote(web.path)} && npm install 'a@1.0.1')"))
        assertTrue(cmd.contains("_ec=\${PIPESTATUS[0]}"))
        assertTrue(cmd.contains("if [ \"\$_ec\" = 0 ]; then CHECKS;"))
        assertTrue(cmd.contains(DependencyUpdates.CHECKED))
    }

    // ---- Saved data ----

    @Test fun `reports and runs survive a round trip`() {
        val report = DepReport(1_700_000_000_000, listOf(
            DepGroup(DepTool.Npm, File("/p"), listOf(OutdatedDep("react", "18.2.0", "18.3.1", "18.3.1"), OutdatedDep("eslint", null, null, "9.0.0", "dev"))),
            DepGroup(DepTool.Cargo, File("/p/rs"), emptyList(), "cargo-outdated isn't installed", failed = true),
        ))
        val back = DependencyUpdates.decode(DependencyUpdates.encode(report))!!
        assertEquals(report.checkedAt, back.checkedAt)
        assertEquals(report.groups.map { it.tool }, back.groups.map { it.tool })
        assertEquals(report.groups[0].deps, back.groups[0].deps)
        assertEquals(report.groups[1].note, back.groups[1].note)
        assertTrue(back.groups[1].failed)
        assertEquals(File("/p/rs").path, back.groups[1].dir.path)
        assertNull(DependencyUpdates.decode("garbage"))

        val run = DependencyUpdates.Run(5, "- react 18.2.0 → 18.3.1 (npm)", "(cd '.' && npm install 'react@18.3.1')", null)
        assertEquals(run, DependencyUpdates.decodeRun(DependencyUpdates.encodeRun(run)))
    }

    @Test fun `the agent gets the update list`() {
        val prompt = FixPrompt.build(FailureReport("Checks", "tsc", listOf("Node.js"), "error TS2322: nope", details = "These dependencies were just updated:\n- react 18 → 19"), emptyList())
        assertTrue(prompt.contains("- react 18 → 19"))
    }
}
