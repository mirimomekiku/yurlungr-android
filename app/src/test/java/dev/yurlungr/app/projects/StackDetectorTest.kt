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
        assertEquals(listOf("openjdk-17"), android.packages)
    }

    @Test fun `several stacks in app folders, each in its own folder`() {
        write("backend/requirements.txt", "flask\n")
        write("frontend/package.json", "{}")
        write("backend/go.mod", "module x")
        val ids = StackDetector.detect(tmp.root).map { it.id to it.dir.name }.toSet()
        assertEquals(setOf("python" to "backend", "go" to "backend", "node" to "frontend"), ids)
        val python = StackDetector.detect(tmp.root).first { it.id == "python" }
        assertEquals("python -m venv .venv", python.steps.first())
        assertEquals(".venv/bin/pip install -r requirements.txt", python.steps.last())
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
