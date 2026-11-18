package dev.yurlungr.app.web

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomServersTest {
    @get:Rule val temp = TemporaryFolder()

    private fun saved(cwd: File, mode: String = "Custom", command: String = ".venv/bin/flask run --port \"\$PORT\"") = JsonObject(mapOf(
        "cwd" to JsonPrimitive(cwd.canonicalPath), "mode" to JsonPrimitive(mode), "command" to JsonPrimitive(command),
    ))

    @Test fun `registered server is offered when detection finds nothing`() {
        val root = temp.newFolder()
        val none = ProjectCapabilities(false, emptyList())
        val merged = CustomServers.merge(root, none, saved(root), "Flask")
        val app = merged.web.single()
        assertEquals(root.canonicalFile, app.directory)
        assertEquals(WebKind.Script, app.kind)
        assertEquals("Flask", app.framework)
        assertEquals(WebLaunchMode.Custom, app.defaultMode)
        // No package.json: Run mustn't try to npm install.
        assertTrue(app.dependenciesPresent)
    }

    @Test fun `saved settings that aren't a custom command, or point elsewhere, add nothing`() {
        val root = temp.newFolder("root")
        val other = temp.newFolder("other")
        val none = ProjectCapabilities(false, emptyList())
        assertSame(none, CustomServers.merge(root, none, null, "Flask"))
        assertSame(none, CustomServers.merge(root, none, saved(root, mode = "Script"), "Flask"))
        assertSame(none, CustomServers.merge(root, none, saved(root, command = " "), "Flask"))
        assertSame(none, CustomServers.merge(root, none, saved(other), "Flask"))
        assertSame(none, CustomServers.merge(root, none, saved(File(root, "gone")), "Flask"))
    }

    @Test fun `a detected project in the same folder wins`() {
        val root = temp.newFolder()
        val detected = ProjectCapabilities(false, listOf(WebProject(root.canonicalFile, root.canonicalFile, WebKind.Vite, "Vite")))
        assertSame(detected, CustomServers.merge(root, detected, saved(root), "Laravel"))
        // A static page in a subfolder (Flutter's web/) doesn't hide the server at the root.
        val web = File(root, "web").apply { mkdirs() }.canonicalFile
        val sub = ProjectCapabilities(false, listOf(WebProject(web, web, WebKind.Static, "Static site")))
        assertEquals(2, CustomServers.merge(root, sub, saved(root), "Flutter").web.size)
    }
}
