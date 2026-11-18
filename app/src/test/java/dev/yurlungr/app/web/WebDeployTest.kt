package dev.yurlungr.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class WebDeployTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file(root: File, path: String, text: String = "x") = File(root, path).apply { parentFile?.mkdirs(); writeText(text) }

    @Test fun `output folder follows the framework`() {
        assertEquals("dist", WebDeploy.outputCandidates(WebKind.Vite, "Vite").first())
        assertEquals("build", WebDeploy.outputCandidates(WebKind.Vite, "SvelteKit").first())
        assertEquals("build", WebDeploy.outputCandidates(WebKind.Cra, "Create React App").first())
        assertEquals("out", WebDeploy.outputCandidates(WebKind.Next, "Next.js").first())
        assertEquals("dist", WebDeploy.outputCandidates(WebKind.Astro, "Astro").first())
        assertEquals(listOf("public", "."), WebDeploy.outputCandidates(WebKind.Static, "Static site"))
    }

    @Test fun `detection prefers a folder with index html, also one level in`() {
        val root = temp.newFolder()
        assertEquals("dist", WebDeploy.detectOutput(root, listOf("dist")))
        file(root, "index.html")
        assertEquals(".", WebDeploy.detectOutput(root, listOf("public", ".")))
        file(root, "public/index.html")
        assertEquals("public", WebDeploy.detectOutput(root, listOf("public", ".")))
        file(root, "dist/my-app/browser/index.html")
        assertEquals("dist/my-app/browser", WebDeploy.detectOutput(root, listOf("dist")))
    }

    @Test fun `an output override must stay inside the project`() {
        val root = temp.newFolder("proj")
        File(root, "dist").mkdirs()
        assertEquals(File(dev.yurlungr.app.projects.SpaceCleaner.realFile(root), "dist").invariantSeparatorsPath, WebDeploy.resolveOutput(root, "dist/")!!.invariantSeparatorsPath)
        assertEquals(dev.yurlungr.app.projects.SpaceCleaner.realFile(root), WebDeploy.resolveOutput(root, " "))
        assertNull(WebDeploy.resolveOutput(root, "../other"))
        assertNull(WebDeploy.resolveOutput(root, "dist/../../x"))
        assertNull(WebDeploy.resolveOutput(root, "~/x"))
        assertNull(WebDeploy.resolveOutput(root, temp.root.absolutePath))
        // Not built yet is fine: it's still inside.
        assertNotNull(WebDeploy.resolveOutput(root, "out"))
    }

    @Test fun `an output folder linked elsewhere is refused`() {
        val root = temp.newFolder("proj2")
        val outside = temp.newFolder("outside")
        assumeTrue("symbolic links unavailable here", runCatching { Files.createSymbolicLink(File(root, "dist").toPath(), outside.toPath()); true }.getOrDefault(false))
        assertNull(WebDeploy.resolveOutput(root, "dist"))
    }

    private fun project(dir: File, scripts: Map<String, String>, manager: WebPackageManager? = WebPackageManager.Pnpm) =
        WebProject(dir, dir, WebKind.Vite, "Vite", manager = manager, scriptCommands = scripts)

    @Test fun `build uses the package manager and installs first when needed`() {
        val dir = temp.newFolder("my app")
        assertNull(WebDeploy.buildCommand(project(dir, mapOf("dev" to "vite"))))
        val cmd = WebDeploy.buildCommand(project(dir, mapOf("build" to "vite build")))!!
        assertTrue(cmd, cmd.contains("pnpm install && "))
        assertTrue(cmd, cmd.endsWith("&& pnpm run build"))
        assertTrue(cmd, cmd.contains("'" + dir.invariantSeparatorsPath + "'"))
        File(dir, "node_modules").mkdirs()
        assertEquals("cd '${dir.invariantSeparatorsPath}' && npm run build", WebDeploy.buildCommand(project(dir, mapOf("build" to "x"), manager = null)))
    }

    @Test fun `deploy commands quote paths, never contain tokens and clear them afterwards`() {
        val dir = File("/home/projects/it's")
        val out = File(dir, "dist")
        val cf = WebDeploy.deployCommand(DeployProvider.Cloudflare, dir, out, "my-site")!!
        assertTrue(cf, cf.contains("npx --yes wrangler pages deploy '/home/projects/it'\\''s/dist' --project-name 'my-site'"))
        assertTrue(cf, cf.contains("unset CLOUDFLARE_API_TOKEN CLOUDFLARE_ACCOUNT_ID"))
        assertTrue(cf, cf.endsWith("(exit \$_deploy_exit)"))
        assertNull("Cloudflare needs a project name", WebDeploy.deployCommand(DeployProvider.Cloudflare, dir, out, ""))
        assertNull(WebDeploy.deployCommand(DeployProvider.Cloudflare, dir, out, "x; rm -rf ~"))
        assertNull(WebDeploy.deployCommand(DeployProvider.Netlify, dir, out, "-oops"))

        val nl = WebDeploy.deployCommand(DeployProvider.Netlify, dir, out, null)!!
        assertTrue(nl, nl.contains("netlify-cli deploy --dir '/home/projects/it'\\''s/dist' --prod --no-build;"))
        assertTrue(WebDeploy.deployCommand(DeployProvider.Netlify, dir, out, "abc-123")!!.contains("--site 'abc-123'"))
        val vc = WebDeploy.deployCommand(DeployProvider.Vercel, dir, out, "shop")!!
        assertTrue(vc, vc.contains("npx --yes vercel deploy '/home/projects/it'\\''s/dist' --prod --yes --project 'shop'"))
        assertTrue(vc, vc.contains("unset VERCEL_TOKEN"))
    }

    @Test fun `deploy environment needs every key`() {
        val missing = WebDeploy.deployEnv(DeployProvider.Cloudflare, mapOf("CLOUDFLARE_API_TOKEN" to "t"))
        assertTrue(missing.isFailure)
        val ok = WebDeploy.deployEnv(DeployProvider.Cloudflare, mapOf("CLOUDFLARE_API_TOKEN" to " t ", "CLOUDFLARE_ACCOUNT_ID" to "a")).getOrThrow()
        assertEquals(mapOf("CLOUDFLARE_API_TOKEN" to "t", "CLOUDFLARE_ACCOUNT_ID" to "a"), ok)
        assertTrue(WebDeploy.deployEnv(DeployProvider.Vercel, mapOf("VERCEL_TOKEN" to "a\nb")).isFailure)
    }

    @Test fun `masking shows at most the last four characters`() {
        assertEquals("••••", WebDeploy.mask("short"))
        assertEquals("••••wxyz", WebDeploy.mask("abcdefghijklmnopqrstuvwxyz"))
        assertFalse(WebDeploy.mask("supersecret-token-1234").contains("secret"))
    }

    @Test fun `zip holds the folder's files, skips git and links, and respects the cap`() {
        val dir = temp.newFolder("dist")
        file(dir, "index.html", "<h1>hi</h1>"); file(dir, "assets/app.js", "1"); file(dir, ".git/HEAD", "ref")
        file(dir, ".env", "TOKEN=secret"); file(dir, "node_modules/x.js"); file(dir, ".well-known/security.txt", "hi")
        val linked = runCatching { Files.createSymbolicLink(File(dir, "secret").toPath(), temp.newFolder("secret").toPath()); true }.getOrDefault(false)
        val zip = File(temp.root, "out/site.zip")
        val summary = WebDeploy.zipFolder(dir, zip)
        assertEquals(3, summary.files)
        if (linked) assertEquals(1, summary.skippedLinks)
        val names = ZipFile(zip).use { z -> z.entries().toList().map { it.name }.sorted() }
        assertEquals(listOf(".well-known/security.txt", "assets/app.js", "index.html"), names)

        val small = File(temp.root, "out/small.zip")
        assertTrue(runCatching { WebDeploy.zipFolder(dir, small, maxBytes = 5) }.isFailure)
        assertFalse("a failed zip leaves nothing behind", small.exists())
    }
}
