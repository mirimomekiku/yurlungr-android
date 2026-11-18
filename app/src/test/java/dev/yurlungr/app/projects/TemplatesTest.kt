package dev.yurlungr.app.projects

import dev.yurlungr.app.web.ProjectDetector
import dev.yurlungr.app.web.WebKind
import dev.yurlungr.app.web.WebLaunchMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemplatesTest {
    @get:Rule val temp = TemporaryFolder()

    private val names = TemplateNames.of("My_App.2")

    /** Writes [id]'s files into a fresh folder, as the app does. */
    private fun write(id: String): File {
        val dir = temp.newFolder("p-$id")
        for ((path, text) in Templates.allFiles(Templates.byId(id), names)) File(dir, path).apply { parentFile?.mkdirs(); writeText(text) }
        return dir
    }

    @Test fun `every requested template exists once`() {
        val ids = Templates.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            listOf("empty", "static", "vite-react", "vite-vue", "vite-svelte", "next", "astro", "express", "hono",
                "fastapi", "flask", "django", "laravel", "go", "axum", "expo", "flutter"),
            ids,
        )
        assertEquals("empty", Templates.byId("nope").id)
        assertTrue(Templates.byId("flutter").experimental)
        assertEquals(listOf("flutter"), Templates.all.filter { it.experimental }.map { it.id })
    }

    @Test fun `names are valid for npm, cargo and dart`() {
        assertEquals("my-app-2", Templates.slug("My_App.2"))
        assertEquals("app", Templates.slug("..."))
        assertEquals("app-2048", Templates.crateName("2048"))
        assertEquals("my-app", Templates.crateName("my-app"))
        assertEquals("my_app_2", Templates.dartName("My_App.2"))
        assertEquals("app_9lives", Templates.dartName("9lives"))
    }

    @Test fun `every template writes AGENTS md and gitignore with safe relative paths`() {
        for (t in Templates.all.filterNot { it.isEmpty }) {
            val files = Templates.allFiles(t, names)
            assertTrue(t.id, files.getValue("AGENTS.md").startsWith("# My_App.2\n"))
            assertTrue(t.id, "## Run" in files.getValue("AGENTS.md"))
            assertTrue(t.id, "Notes for this phone" in files.getValue("AGENTS.md"))
            assertTrue(t.id, files.getValue(".gitignore").lines().size > 1)
            for (path in files.keys) {
                assertFalse("$t: $path", path.startsWith("/") || path.split('/').any { it == ".." || it.isEmpty() })
            }
            // The runner (web-runner.cjs) is a Node script: every runnable template needs Node.
            assertTrue(t.id, t.requires.any { it.marker == "bin/node" })
            assertNotNull(t.id, t.guide)
        }
    }

    @Test fun `package json manifests parse and pin major versions`() {
        for (t in Templates.all) {
            val pkg = t.files(names)["package.json"] ?: continue
            val json = Json.parseToJsonElement(pkg).jsonObject
            assertEquals(t.id, "my-app-2", json.getValue("name").jsonPrimitive.content)
            for (section in listOf("dependencies", "devDependencies")) {
                json[section]?.jsonObject?.values?.forEach { v ->
                    val version = v.jsonPrimitive.content
                    assertTrue("${t.id}: $version", version.matches(Regex("[~^]\\d+\\.\\d+\\.\\d+")))
                }
            }
        }
    }

    @Test fun `node templates are found by Run's detector with the expected launch`() {
        fun web(id: String) = ProjectDetector.detect(write(id)).web.single()
        assertEquals(WebKind.Static, web("static").kind)
        listOf("vite-react", "vite-vue", "vite-svelte").forEach { id ->
            val app = web(id)
            assertEquals(id, WebKind.Vite, app.kind)
            assertEquals(id, WebLaunchMode.Script, app.defaultMode)
            assertEquals(id, "dev", app.scripts.first())
        }
        assertEquals(WebKind.Next, web("next").kind)
        assertEquals(WebKind.Astro, web("astro").kind)
        for (id in listOf("express", "hono")) {
            val app = web(id)
            assertEquals(id, WebKind.Script, app.kind)
            assertEquals(id, "dev", app.scripts.first())
            assertFalse(id, app.dependenciesPresent)
        }
    }

    @Test fun `servers the detector can't find have a run command on 127 0 0 1 and PORT`() {
        for (id in listOf("fastapi", "flask", "django", "laravel", "go", "axum", "flutter")) {
            val t = Templates.byId(id)
            val server = assertNotNullAndGet(t.server, id)
            if (id !in setOf("go", "axum")) assertTrue(id, "127.0.0.1" in server.command)
            if (id !in setOf("go", "axum")) assertTrue(id, "\$PORT" in server.command)
            assertTrue(id, server.startupTimeoutSeconds in 30..900)
        }
        // Go and Rust read PORT themselves and bind 127.0.0.1 in code.
        assertTrue("os.Getenv(\"PORT\")" in Templates.byId("go").files(names).getValue("main.go"))
        assertTrue("127.0.0.1:" in Templates.byId("go").files(names).getValue("main.go"))
        assertTrue("[127, 0, 0, 1]" in Templates.byId("axum").files(names).getValue("src/main.rs"))
        // Python servers are invisible to the web detector: that's why they carry a server run.
        assertTrue(ProjectDetector.detect(write("flask")).web.isEmpty())
        // Node templates are detected, so they don't need one.
        for (id in listOf("static", "vite-react", "next", "astro", "express", "hono", "expo")) assertNull(id, Templates.byId(id).server)
    }

    private fun <T> assertNotNullAndGet(value: T?, message: String): T { assertNotNull(message, value); return value!! }

    @Test fun `setup comes from the stack detector unless the template scaffolds`() {
        val static = write("static")
        assertNull(Templates.setupCommand(Templates.byId("static"), static, names, StackDetector.detect(static)))

        val flask = write("flask")
        val stacks = StackDetector.detect(flask)
        assertEquals(listOf("python"), stacks.map { it.id })
        val command = Templates.setupCommand(Templates.byId("flask"), flask, names, stacks)!!
        assertTrue(command, "pip install -r requirements.txt" in command)

        val react = write("vite-react")
        assertTrue(Templates.setupCommand(Templates.byId("vite-react"), react, names, StackDetector.detect(react))!!.contains("npm install"))

        val go = write("go")
        assertTrue(Templates.setupCommand(Templates.byId("go"), go, names, StackDetector.detect(go))!!.contains("go mod download"))
        val rust = write("axum")
        assertTrue(Templates.setupCommand(Templates.byId("axum"), rust, names, StackDetector.detect(rust))!!.contains("cargo fetch"))
    }

    @Test fun `scaffolded templates use their CLIs`() {
        val dir = temp.newFolder("scaffold")
        val laravel = Templates.setupCommand(Templates.byId("laravel"), dir, names, emptyList())!!
        assertTrue(laravel, "composer create-project" in laravel && "laravel/laravel" in laravel)
        assertTrue(laravel, "'php composer nodejs-lts'" in laravel)
        val expo = Templates.setupCommand(Templates.byId("expo"), dir, names, emptyList())!!
        assertTrue(expo, "create-expo-app@latest" in expo && "react-native-web" in expo)
        assertTrue(expo, "${Templates.SCAFFOLD_DIR}/my-app-2" in expo)
        val flutter = Templates.setupCommand(Templates.byId("flutter"), dir, names, emptyList())!!
        assertTrue(flutter, "yurlungr-ubuntu" in flutter && "--project-name my_app_2" in flutter && "web,android" in flutter)
        assertTrue(Templates.byId("flutter").server!!.command.startsWith("yurlungr-ubuntu \"flutter run -d web-server"))
    }

    @Test fun `missing tools name the packs to install`() {
        val prefix = temp.newFolder("usr")
        File(prefix, "bin").mkdirs()
        File(prefix, "bin/node").writeText("")
        assertTrue(Templates.missing(Templates.byId("vite-react"), prefix).isEmpty())
        assertNull(Templates.missingMessage(emptyList()))

        val fastapi = Templates.missing(Templates.byId("fastapi"), prefix)
        assertEquals(listOf("Python", "Rust"), fastapi.map { it.label })
        assertEquals("Install Python and Rust in System checks first.", Templates.missingMessage(fastapi))

        File(prefix, "bin/python").writeText("")
        assertEquals("Install Rust in System checks first.", Templates.missingMessage(Templates.missing(Templates.byId("fastapi"), prefix)))

        val flutter = Templates.missing(Templates.byId("flutter"), prefix)
        assertEquals("Install Ubuntu environment and Flutter (experimental) in System checks first.", Templates.missingMessage(flutter))
        assertTrue(flutter.all { it.pack == null })
        File(prefix, "bin/yurlungr-ubuntu").writeText("")
        File(prefix, "var/lib/proot-distro/installed-rootfs/ubuntu/opt/flutter/bin").mkdirs()
        File(prefix, "var/lib/proot-distro/installed-rootfs/ubuntu/opt/flutter/bin/flutter").writeText("")
        assertTrue(Templates.missing(Templates.byId("flutter"), prefix).isEmpty())

        val none = temp.newFolder("empty-prefix")
        assertEquals("Install Developer tools in System checks first.", Templates.missingMessage(Templates.missing(Templates.byId("static"), none)))
        assertTrue(Templates.missing(Templates.byId("empty"), none).isEmpty())
    }

    @Test fun `AGENTS md tells how to open an Expo app in Expo Go and is honest about Flutter`() {
        val expo = Templates.agentsMd(Templates.byId("expo"), names)
        assertTrue("exp://127.0.0.1:8081" in expo && "Expo Go" in expo)
        val flutter = Templates.agentsMd(Templates.byId("flutter"), names)
        assertTrue("(experimental)" in flutter && "APK" in flutter && "web" in flutter)
    }

    @Test fun `django settings use the generated secret`() {
        val files = Templates.byId("django").files(TemplateNames.of("site", secret = "django-insecure-abc"))
        assertTrue("\"django-insecure-abc\"" in files.getValue("config/settings.py"))
        assertTrue(files.containsKey("manage.py"))
    }

    @Test fun `exit marker keeps the command's status and writes atomically`() {
        val marker = File(temp.root, "launch/x.exit")
        val command = ProjectLaunch.withExitMarker("echo 'hi'", marker)
        assertTrue(command, command.startsWith("( echo 'hi' ); _yl_rc=\$?;"))
        assertTrue(command, command.endsWith("(exit \$_yl_rc)"))
        assertTrue(command, "mv -f '${marker.path}.tmp' '${marker.path}'" in command)
        assertNull(ProjectLaunch.readExit(marker))
        marker.parentFile!!.mkdirs()
        marker.writeText("0")
        assertEquals(0, ProjectLaunch.readExit(marker))
        marker.writeText("130\n")
        assertEquals(130, ProjectLaunch.readExit(marker))
        marker.writeText("")
        assertNull(ProjectLaunch.readExit(marker))
    }
}
