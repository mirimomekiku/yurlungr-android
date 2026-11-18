package dev.yurlungr.app.projects

import dev.yurlungr.app.runtime.projectSetupCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StackDetectorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(path: String, text: String = "") = File(tmp.root, path).apply { parentFile?.mkdirs(); writeText(text) }

    @Test fun `expo with yarn is found, with its framework and manager`() {
        write("package.json", """{"dependencies": {"expo": "~52.0.0", "react-native": "0.76.0", "react": "18.3.1"}}""")
        write("yarn.lock")
        val node = StackDetector.detect(tmp.root).single()
        assertEquals("node", node.id)
        assertEquals("Node.js (yarn)", node.name)
        assertTrue(node.tags.containsAll(listOf("Expo", "React Native", "React")))
        assertEquals(listOf("nodejs-lts", "yarn"), node.packages)
        assertEquals(listOf("yarn install"), node.steps)
        assertEquals(false, node.installed)
        assertTrue(node.notes.any { "expo start" in it })
    }

    @Test fun `npm uses ci with a lockfile, and node_modules means installed`() {
        write("package.json", """{"devDependencies": {"vite": "^6"}}""")
        write("package-lock.json", "{}")
        File(tmp.root, "node_modules").mkdirs()
        val node = StackDetector.detect(tmp.root).single()
        assertEquals(listOf("npm ci || npm install"), node.steps)
        assertTrue("Vite" in node.tags)
        assertEquals(true, node.installed)
    }

    @Test fun `workspaces install once at the root`() {
        write("package.json", """{"private": true, "workspaces": ["apps/*"]}""")
        write("pnpm-lock.yaml")
        write("apps/web/package.json", """{"dependencies": {"next": "15"}}""")
        val stacks = StackDetector.detect(tmp.root)
        assertEquals(1, stacks.size)
        assertEquals(tmp.root.canonicalPath, stacks.single().dir.canonicalPath)
        assertTrue(stacks.single().steps.last() == "pnpm install")
    }

    @Test fun `flutter is explained, not installed, and its android folder isn't a separate build`() {
        write("pubspec.yaml", "name: app\ndependencies:\n  flutter:\n    sdk: flutter\n")
        write("android/build.gradle", "plugins { id 'com.android.application' }")
        val stacks = StackDetector.detect(tmp.root)
        val flutter = stacks.single()
        assertEquals("flutter", flutter.id)
        assertNotNull(flutter.unsupported)
        assertFalse(flutter.canInstall)
    }

    @Test fun `pure dart uses dart pub get`() {
        write("pubspec.yaml", "name: tool\ndependencies:\n  args: ^2.0.0\n")
        val dart = StackDetector.detect(tmp.root).single()
        assertEquals("dart", dart.id)
        assertEquals(listOf("dart pub get"), dart.steps)
    }

    @Test fun `android compose project at the root, modules not repeated`() {
        write("settings.gradle.kts", "include(\":app\")")
        write("build.gradle.kts", "plugins { alias(libs.plugins.kotlin.android) }")
        write("app/build.gradle.kts", "plugins { id(\"com.android.application\") }\nandroid { buildFeatures { compose = true } }")
        write("gradlew", "#!/bin/sh")
        val stacks = StackDetector.detect(tmp.root)
        val android = stacks.single()
        assertEquals("android", android.id)
        assertTrue(android.tags.containsAll(listOf("Kotlin", "Jetpack Compose", "Android")))
        assertEquals(listOf("chmod +x gradlew", "./gradlew --no-daemon dependencies"), android.steps)
        assertEquals(listOf("openjdk-21"), android.packages)
    }

    @Test fun `several stacks in app folders, each in its own folder`() {
        write("backend/requirements.txt", "flask\n")
        write("frontend/package.json", "{}")
        write("backend/go.mod", "module x")
        val ids = StackDetector.detect(tmp.root).map { it.id to it.dir.name }.toSet()
        assertEquals(setOf("python" to "backend", "go" to "backend", "node" to "frontend"), ids)
        val python = StackDetector.detect(tmp.root).first { it.id == "python" }
        // Made once, and able to import Termux's prebuilt numpy, pandas… instead of compiling them.
        assertEquals(StackDetector.VENV, python.steps.first())
        assertTrue(python.steps.first().contains("python -m venv --system-site-packages .venv"))
        assertTrue(python.steps.first().contains("uv venv --seed --system-site-packages"))
        assertEquals(".venv/bin/pip install -r requirements.txt", python.steps.last())
    }

    @Test fun `bun, pnpm and yarn berry use their own tools`() {
        write("package.json", """{"devDependencies": {"vite": "6"}}""")
        write("bun.lock")
        StackDetector.detect(tmp.root).single().let {
            assertEquals("Node.js (bun)", it.name)
            assertEquals(listOf("nodejs-lts", "bun"), it.packages)
            assertEquals(listOf("bun install"), it.steps)
        }
        File(tmp.root, "bun.lock").delete()
        write("pnpm-lock.yaml")
        assertEquals(listOf("nodejs-lts", "pnpm"), StackDetector.detect(tmp.root).single().packages)
        File(tmp.root, "pnpm-lock.yaml").delete()
        write("package.json", """{"packageManager": "yarn@4.5.0"}""")
        StackDetector.detect(tmp.root).single().let {
            assertEquals(listOf("nodejs-lts"), it.packages)
            assertTrue(it.steps.single().contains("corepack yarn install"))
        }
    }

    @Test fun `python projects use uv or poetry when they do`() {
        write("pyproject.toml", "[project]\nname = 'x'\n")
        write("uv.lock")
        StackDetector.detect(tmp.root).single().let {
            assertEquals("Python (uv)", it.name)
            assertEquals(listOf("python", "uv"), it.packages)
            assertEquals(listOf(StackDetector.UV_VENV, "uv sync"), it.steps)
        }
        File(tmp.root, "uv.lock").delete()
        write("pyproject.toml", "[tool.poetry]\nname = 'x'\n")
        assertTrue(StackDetector.detect(tmp.root).single().steps.last().contains("poetry install"))
    }

    @Test fun `more stacks by their manifests`() {
        write("deno.json", "{}")
        write("mix.exs", "defmodule X.MixProject do end")
        write("build.zig")
        write("App.csproj", "<Project/>")
        write("my lib-1.0-1.rockspec")
        val byId = StackDetector.detect(tmp.root).associateBy { it.id }
        assertEquals(listOf("deno install"), byId.getValue("deno").steps)
        assertEquals(listOf("erlang", "elixir"), byId.getValue("elixir").packages)
        assertEquals(listOf("zig build --fetch"), byId.getValue("zig").steps)
        assertEquals("App.csproj", byId.getValue("dotnet").evidence)
        // A file name with spaces stays one argument.
        assertEquals("luarocks install --local --only-deps 'my lib-1.0-1.rockspec'", byId.getValue("lua").steps.single())
    }

    @Test fun `runtimes are missing until dpkg lists them, and the summary says what's needed`() {
        val prefix = tmp.newFolder("usr")
        write("package.json", "{}")
        write("pnpm-lock.yaml")
        val node = StackDetector.detect(tmp.root).single()
        assertEquals(listOf("nodejs-lts", "pnpm"), node.missingPackages(prefix))
        assertTrue(node.needsSetup(prefix))
        assertEquals("Needs Node.js and pnpm · dependencies not installed", setupSummary(listOf(node), prefix))
        File(prefix, "var/lib/dpkg/info").mkdirs()
        File(prefix, "var/lib/dpkg/info/nodejs-lts.list").writeText("")
        File(prefix, "var/lib/dpkg/info/pnpm.list").writeText("")
        File(tmp.root, "node_modules").mkdirs()
        val ready = StackDetector.detect(tmp.root).single()
        assertTrue(ready.missingPackages(prefix).isEmpty())
        assertFalse(ready.needsSetup(prefix))
    }

    @Test fun `native add-ons bring what compiling them needs`() {
        write("package.json", """{"dependencies": {"sharp": "0.34", "better-sqlite3": "12"}}""")
        val node = StackDetector.detect(tmp.root).single()
        assertTrue(node.packages.containsAll(listOf("nodejs-lts", "python", "clang", "make", "pkg-config", "libvips", "libsqlite")))
        assertEquals(node.packages.distinct(), node.packages)
        // esbuild ships prebuilt: nothing to compile.
        write("package.json", """{"devDependencies": {"esbuild": "0.25"}}""")
        assertEquals(listOf("nodejs-lts"), StackDetector.detect(tmp.root).single().packages)
    }

    @Test fun `nothing known, nothing found`() {
        write("notes.txt", "hi")
        assertTrue(StackDetector.detect(tmp.root).isEmpty())
    }

    @Test fun `install command quotes every value and enters subfolders`() {
        val frontend = File(tmp.root, "frontend")
        val stack = DetectedStack("node", "Node.js (npm)", frontend, "frontend/package.json", packages = listOf("nodejs-lts"), steps = listOf("npm install"))
        val cmd = installCommand(tmp.root, listOf(stack))
        assertTrue(cmd.startsWith("bash -n \"\$PREFIX/libexec/yurlungr/project-setup.sh\" && bash "))
        assertTrue(cmd.contains("'nodejs-lts'"))
        assertTrue(cmd.contains("'cd '\\''frontend'\\'' && npm install'"))
        // A step containing a single quote stays one argument.
        val quoted = projectSetupCommand(tmp.root, emptyList(), listOf("echo 'hi'"))
        assertTrue(quoted.endsWith("'' 'echo '\\''hi'\\'''"))
        assertNull(runCatching { projectSetupCommand(tmp.root, listOf("bad;rm"), listOf("x")) }.getOrNull())
    }
}
