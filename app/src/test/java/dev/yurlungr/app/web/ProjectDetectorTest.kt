package dev.yurlungr.app.web

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectDetectorTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file(root: File, path: String, contents: String = "") = File(root,path).apply { parentFile?.mkdirs(); writeText(contents) }

    @Test fun `package json alone and development libraries do not imply web app`() {
        val root=temp.newFolder()
        file(root,"package.json", """{"scripts":{"dev":"node server.js"},"devDependencies":{"typescript":"*"}}""")
        assertTrue(ProjectDetector.detect(root).web.isEmpty())
    }
    @Test fun `Vite runnable script and Android build coexist`() {
        val root=temp.newFolder()
        file(root,"gradlew"); file(root,"index.html")
        file(root,"package.json", """{"scripts":{"build":"vite build","preview":"vite preview","dev":"vite --mode development"},"devDependencies":{"vite":"*"},"packageManager":"pnpm@10.1.0"}""")
        val capabilities=ProjectDetector.detect(root)
        assertTrue(capabilities.android)
        val app=capabilities.web.single()
        assertEquals(WebKind.Vite,app.kind); assertEquals(listOf("dev"),app.scripts)
        assertEquals(WebPackageManager.Pnpm,app.manager)
    }
    @Test fun `monorepo uses workspace install root and avoids node modules`() {
        val root=temp.newFolder()
        file(root,"package.json", """{"workspaces":["apps/*"],"packageManager":"npm@11.0.0"}""")
        file(root,"apps/client/package.json", """{"scripts":{"dev":"vite"}}""")
        file(root,"node_modules/fixture/index.html")
        val app=ProjectDetector.detect(root).web.single()
        assertEquals(File(root,"apps/client"),app.directory)
        assertEquals(root,app.installDirectory)
    }
    @Test fun `Next and Vite build only projects are candidates instead of static sites`() {
        val root=temp.newFolder()
        file(root,"index.html"); file(root,"package.json", """{"dependencies":{"next":"*"}}""")
        assertEquals(WebKind.Candidate,ProjectDetector.detect(root).web.single().kind)
        file(root,"package.json", """{"devDependencies":{"vite":"*"},"scripts":{"build":"vite build"}}""")
        assertEquals(WebKind.Candidate,ProjectDetector.detect(root).web.single().kind)
    }
    @Test fun `static site and conflicting lock files require explicit manager selection`() {
        val root=temp.newFolder(); file(root,"index.html"); file(root,"package-lock.json"); file(root,"yarn.lock")
        val app=ProjectDetector.detect(root).web.single()
        assertEquals(WebKind.Static,app.kind); assertNull(app.manager)
    }
    @Test fun `shell compound commands are never given guessed Vite flags`() {
        for (command in listOf("echo vite", "vite build", "vite preview", "vite && echo ready", "vite; node worker.js", "vite | cat", "vite > log")) {
            assertFalse(command,ProjectDetector.isViteDevScript(command))
        }
        assertTrue(ProjectDetector.isViteDevScript("vite --config vite.config.ts"))
    }
    @Test fun `port validation and serialized configuration preserve paths as data`() {
        val root=temp.newFolder("a 'quoted' app"); file(root,"index.html")
        val project=ProjectDetector.detect(root).web.single()
        val config=WebRunConfig(project,WebPackageManager.Npm,"",8080,false)
        config.validate()
        assertTrue(config.toJson("run-id",File("/node"),File("/bash")).toString().contains("quoted"))
        assertThrows(IllegalArgumentException::class.java) { config.copy(port=80).validate() }
        assertThrows(IllegalArgumentException::class.java) { config.copy(port=65536).validate() }
    }
}
