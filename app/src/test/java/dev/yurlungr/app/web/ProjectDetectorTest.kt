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
    @Test fun `known frameworks start with their own dev command instead of being static sites`() {
        val root=temp.newFolder()
        file(root,"index.html"); file(root,"package.json", """{"dependencies":{"next":"*"}}""")
        val next=ProjectDetector.detect(root).web.single()
        assertEquals(WebKind.Framework,next.kind); assertEquals("Next.js",next.framework)
        assertTrue(next.launch!!.startsWith("next dev -H 127.0.0.1"))
        assertTrue("warns about the WebAssembly compiler", next.notes.isNotEmpty())
        // Vite without a plain `vite` dev script (e.g. "tsc && vite"): run Vite directly.
        file(root,"package.json", """{"devDependencies":{"vite":"*"},"scripts":{"dev":"tsc && vite","build":"vite build"}}""")
        val vite=ProjectDetector.detect(root).web.single()
        assertEquals(WebKind.Framework,vite.kind)
        assertEquals("vite --host 127.0.0.1 --port \"\$PORT\" --strictPort",vite.launch)
    }
    @Test fun `framework checks come before Vite, and every preset binds loopback and PORT`() {
        val root=temp.newFolder()
        file(root,"package.json", """{"devDependencies":{"vite":"*","@sveltejs/kit":"*","astro":"*"}}""")
        assertEquals("Astro",ProjectDetector.detect(root).web.single().framework)
        for (fw in listOf("Vite","SvelteKit","Next.js","Astro","Nuxt","Angular","Expo (web)")) {
            val cmd=ProjectDetector.launchCommand(fw)!!
            assertTrue(fw,cmd.contains("\$PORT"))
        }
        assertEquals("react-scripts start",ProjectDetector.launchCommand("Create React App"))
        assertNull(ProjectDetector.launchCommand("Remix"))
    }
    @Test fun `an unknown setup runs once given a start command`() {
        val root=temp.newFolder(); file(root,"index.html")
        val project=ProjectDetector.detect(root).web.single().copy(kind=WebKind.Candidate)
        val config=WebRunConfig(project,WebPackageManager.Npm,"",5173,false)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
        config.copy(customCommand="node server.js --port \$PORT").validate()
    }
    @Test fun `a taken port moves to the next free one`() {
        java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { taken ->
            assertNotEquals(taken.localPort,WebQuickRun.freePort(taken.localPort))
        }
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
