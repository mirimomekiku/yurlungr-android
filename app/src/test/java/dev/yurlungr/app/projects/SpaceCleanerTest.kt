package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SpaceCleanerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(root: File, path: String, text: String = "x") = File(root, path).apply { parentFile?.mkdirs(); writeText(text) }

    /** A home with ~/projects; links need privileges on Windows, so tests using them are skipped there. */
    private fun home(): Pair<File, File> {
        val home = temp.newFolder("home")
        return home to File(home, "projects").apply { mkdirs() }
    }

    private fun link(at: File, to: File): Boolean = runCatching { Files.createSymbolicLink(at.toPath(), to.toPath()); true }.getOrDefault(false)

    @Test fun `ambiguous names count only beside their build file`() {
        val p = temp.newFolder("app")
        assertEquals(JunkKind.NodeModules, SpaceCleaner.classify("node_modules", p))
        assertNull(SpaceCleaner.classify("build", p))
        assertNull(SpaceCleaner.classify("vendor", p))
        assertNull(SpaceCleaner.classify("src", p))
        file(p, "build.gradle.kts"); file(p, "composer.json")
        assertEquals(JunkKind.Build, SpaceCleaner.classify("build", p))
        assertEquals(JunkKind.Vendor, SpaceCleaner.classify("vendor", p))
        assertNull(SpaceCleaner.classify("target", p))
        file(p, "Cargo.toml")
        assertEquals(JunkKind.Target, SpaceCleaner.classify("target", p))
    }

    @Test fun `scan finds nested folders, skips git and does not descend into finds`() {
        val p = temp.newFolder("web")
        file(p, "package.json", "{}")
        file(p, "node_modules/a/index.js")
        file(p, "node_modules/b/node_modules/c.js")
        file(p, "dist/index.html")
        file(p, "src/build/keep.ts") // no build file beside it: source
        file(p, "api/__pycache__/m.pyc"); file(p, "api/sub/__pycache__/n.pyc")
        file(p, ".git/node_modules/x")
        file(p, "android/app/build.gradle"); file(p, "android/app/build/out.apk")
        val found = SpaceCleaner.find(p)
        val rels = found.map { it.dir.relativeTo(p).invariantSeparatorsPath }.sorted()
        assertEquals(listOf("android/app/build", "api/__pycache__", "api/sub/__pycache__", "dist", "node_modules"), rels)
        val items = SpaceCleaner.items(p, found)
        val py = items.single { it.title.startsWith("__pycache__") }
        assertEquals(2, py.dirs.size)
        assertEquals(4, items.size)
    }

    @Test fun `gitignore decides the caution on ambiguous folders`() {
        val p = temp.newFolder("site")
        file(p, "package.json", "{}"); file(p, "dist/a.js")
        assertNull(SpaceCleaner.ignoredByGit("dist", p, p))
        file(p, ".gitignore", "node_modules\n# dist\n")
        assertEquals(false, SpaceCleaner.ignoredByGit("dist", p, p))
        assertTrue(SpaceCleaner.items(p, SpaceCleaner.find(p)).single().caution != null)
        file(p, ".gitignore", "/dist/\n")
        assertEquals(true, SpaceCleaner.ignoredByGit("dist", p, p))
        assertNull(SpaceCleaner.items(p, SpaceCleaner.find(p)).single().caution)
    }

    @Test fun `size counts files below the folder`() {
        val p = temp.newFolder("sz")
        file(p, "a", "12345"); file(p, "d/b", "123")
        assertEquals(8L to true, SpaceCleaner.sizeOf(p))
        assertFalse(SpaceCleaner.sizeOf(p, maxEntries = 1).second)
    }

    @Test fun `only regenerable folders inside a project or the known caches are deletable`() {
        val (home, projects) = home()
        val p = File(projects, "app").apply { mkdirs() }
        file(p, "package.json", "{}")
        file(p, "node_modules/x.js"); file(p, "src/main.js"); file(p, ".git/HEAD"); file(p, "build/x")
        assertTrue(SpaceCleaner.isDeletable(File(p, "node_modules"), projects, home))
        assertTrue(SpaceCleaner.isDeletable(File(p, "build"), projects, home)) // package.json beside it
        assertFalse(SpaceCleaner.isDeletable(File(p, "src"), projects, home))
        assertFalse(SpaceCleaner.isDeletable(File(p, ".git"), projects, home))
        assertFalse(SpaceCleaner.isDeletable(File(p, ".git/node_modules"), projects, home))
        assertFalse(SpaceCleaner.isDeletable(p, projects, home)) // a project itself
        assertFalse(SpaceCleaner.isDeletable(projects, projects, home))
        assertFalse(SpaceCleaner.isDeletable(File(projects, "node_modules"), projects, home)) // not inside a project
        assertFalse(SpaceCleaner.isDeletable(File(p, "node_modules/../src"), projects, home))
        assertFalse(SpaceCleaner.isDeletable(File(home, "node_modules"), projects, home)) // outside ~/projects
        assertFalse(SpaceCleaner.isDeletable(File(temp.root, "elsewhere/node_modules"), projects, home))
        // Caches: exactly the listed folders, nothing above or below them.
        assertTrue(SpaceCleaner.isDeletable(File(home, ".npm/_cacache"), projects, home))
        assertTrue(SpaceCleaner.isDeletable(File(home, "go/pkg/mod"), projects, home))
        assertFalse(SpaceCleaner.isDeletable(File(home, ".npm"), projects, home))
        assertFalse(SpaceCleaner.isDeletable(File(home, ".gradle"), projects, home))
        assertFalse(SpaceCleaner.isDeletable(File(home, ".gradle/caches/modules-2"), projects, home))
        assertFalse(SpaceCleaner.isDeletable(home, projects, home))
    }

    @Test fun `delete removes the folder and refuses anything else`() {
        val (home, projects) = home()
        val p = File(projects, "app").apply { mkdirs() }
        val src = file(p, "src/main.js")
        file(p, "node_modules/pkg/index.js")
        assertTrue(SpaceCleaner.delete(File(p, "node_modules"), projects, home).isSuccess)
        assertFalse(File(p, "node_modules").exists())
        assertTrue(SpaceCleaner.delete(File(p, "src"), projects, home).isFailure)
        assertTrue(src.isFile)
        val cache = file(home, ".cache/pip/http/a")
        assertTrue(SpaceCleaner.delete(File(home, ".cache/pip"), projects, home).isSuccess)
        assertFalse(cache.exists())
        assertTrue(File(home, ".cache").isDirectory)
    }

    @Test fun `links are unlinked, never followed`() {
        val (home, projects) = home()
        val p = File(projects, "app").apply { mkdirs() }
        val outside = temp.newFolder("precious")
        val keep = file(outside, "keep.txt")
        val nm = File(p, "node_modules").apply { mkdirs() }
        assumeTrue("symbolic links unavailable here", link(File(nm, "evil"), outside))
        // A link inside a regenerable folder: removed, its target untouched.
        assertTrue(SpaceCleaner.delete(nm, projects, home).isSuccess)
        assertTrue(keep.isFile)
        // The scan neither reports nor walks through a linked folder.
        val other = temp.newFolder("other"); file(other, "node_modules/x")
        assertTrue(link(File(p, "linked"), other))
        assertTrue(SpaceCleaner.find(p).isEmpty())
        // A path reached through a link is judged where it really is: outside ~/projects.
        assertFalse(SpaceCleaner.isDeletable(File(p, "linked/node_modules"), projects, home))
        assertTrue(File(other, "node_modules/x").isFile)
        // A linked cache parent (~/.npm -> elsewhere) is not the cache folder.
        val elsewhere = temp.newFolder("npm-elsewhere"); File(elsewhere, "_cacache").mkdirs()
        assertTrue(link(File(home, ".npm"), elsewhere))
        assertFalse(SpaceCleaner.isDeletable(File(home, ".npm/_cacache"), projects, home))
    }

    @Test fun `sizes read nicely`() {
        assertEquals("512 B", SpaceCleaner.formatBytes(512))
        assertEquals("2 KB", SpaceCleaner.formatBytes(2048))
        assertEquals("1.5 MB", SpaceCleaner.formatBytes(1024L * 1536))
        assertEquals("2.00 GB", SpaceCleaner.formatBytes(2L * 1024 * 1024 * 1024))
    }
}
