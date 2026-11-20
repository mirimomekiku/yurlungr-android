package dev.yurlungr.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebRunWatcherTest {
    @get:Rule val temp = TemporaryFolder()

    private fun project(kind: WebKind, scripts: Map<String, String> = emptyMap()) =
        WebProject(File("app"), File("app"), kind, kind.name, scripts.keys.toList(), scriptCommands = scripts)

    @Test fun ignoresDependencyBuildAndDotFolders() {
        for (name in listOf(".git", "node_modules", ".venv", "build", "dist", "target", ".gradle", "__pycache__", ".next", ".idea")) {
            assertTrue(name, FileWatch.ignoredDir(name))
        }
        for (name in listOf("src", "app", "templates", "static", "cmd")) assertFalse(name, FileWatch.ignoredDir(name))
    }

    @Test fun ignoresFilesServersWriteThemselves() {
        for (name in listOf("server.log", "app.db", "db.sqlite3", "db.sqlite3-journal", "data.db-wal", "x.pyc", "main.go~", ".main.py.swp", ".#notes")) {
            assertTrue(name, FileWatch.ignoredFile(name))
        }
        for (name in listOf("main.py", "server.js", ".env", "main.go", "index.html", "Cargo.toml")) assertFalse(name, FileWatch.ignoredFile(name))
    }

    @Test fun diffsSnapshots() {
        val before = mapOf("a.py" to FileWatch.Stamp(1, 10), "b.py" to FileWatch.Stamp(1, 10), "c.py" to FileWatch.Stamp(1, 10))
        val after = mapOf("a.py" to FileWatch.Stamp(1, 10), "b.py" to FileWatch.Stamp(2, 10), "d.py" to FileWatch.Stamp(1, 1))
        val changes = FileWatch.diff(before, after)
        assertEquals(listOf("d.py"), changes.added)
        assertEquals(listOf("c.py"), changes.removed)
        assertEquals(listOf("b.py"), changes.modified)
        assertTrue(changes.any)
        assertFalse(FileWatch.diff(before, before).any)
        // Same time, different size still counts (coarse mtimes on some filesystems).
        assertTrue(FileWatch.diff(mapOf("a" to FileWatch.Stamp(1, 1)), mapOf("a" to FileWatch.Stamp(1, 2))).modified.isNotEmpty())
    }

    @Test fun snapshotsSkipIgnoredAndStopAtTheCap() {
        val root = temp.root
        File(root, "src").mkdirs(); File(root, "src/main.py").writeText("print(1)")
        File(root, "app.py").writeText("x")
        File(root, "node_modules/pkg").mkdirs(); File(root, "node_modules/pkg/index.js").writeText("x")
        File(root, ".git").mkdirs(); File(root, ".git/HEAD").writeText("ref")
        File(root, "server.log").writeText("log")
        File(root, ".env").writeText("A=1")
        val snapshot = FileWatch.snapshot(root)
        assertEquals(setOf(".env", "app.py", "src/main.py"), snapshot.keys)
        assertEquals(2, FileWatch.snapshot(root, max = 2).size)
    }

    @Test fun restartsServersThatDontReloadThemselves() {
        val custom = project(WebKind.Script)
        for (command in listOf("python app.py", "flask run", "uvicorn main:app", "go run .", "cargo run", "node server.js", "./bin/server")) {
            assertTrue(command, RestartRule.defaultFor(custom, WebLaunchMode.Custom, "", command))
        }
        for (command in listOf("uvicorn main:app --reload", "flask run --debug", "FLASK_DEBUG=1 flask run", "python manage.py runserver",
            "node --watch server.js", "nodemon server.js", "air", "cargo watch -x run", "fastapi dev main.py", "npx vite", "php -S 127.0.0.1:\$PORT")) {
            assertFalse(command, RestartRule.defaultFor(custom, WebLaunchMode.Custom, "", command))
        }
        assertTrue(RestartRule.defaultFor(custom, WebLaunchMode.Custom, "", "python manage.py runserver --noreload"))
    }

    @Test fun hotReloadingFrameworksStayOff() {
        val vite = project(WebKind.Vite, mapOf("dev" to "vite", "serve" to "node server.js"))
        assertFalse(RestartRule.defaultFor(vite, WebLaunchMode.Framework, "", ""))
        assertFalse(RestartRule.defaultFor(vite, WebLaunchMode.Script, "dev", ""))
        assertFalse(RestartRule.defaultFor(project(WebKind.Next, mapOf("dev" to "next dev")), WebLaunchMode.Script, "dev", ""))
        assertFalse(RestartRule.defaultFor(project(WebKind.Static), WebLaunchMode.Custom, "", "python app.py"))
        // A plain Node server script, and `npm run` judged by the script it runs.
        assertTrue(RestartRule.defaultFor(vite, WebLaunchMode.Script, "serve", ""))
        assertFalse(RestartRule.defaultFor(vite, WebLaunchMode.Custom, "", "npm run dev"))
        assertTrue(RestartRule.defaultFor(vite, WebLaunchMode.Custom, "", "npm run serve"))
    }

    @Test fun explicitChoiceWins() {
        val config = WebRunConfig(project(WebKind.Script, mapOf("start" to "node server.js")), WebPackageManager.Npm, "start", 3000, install = false)
        assertTrue(config.restartsOnChange)
        assertFalse(config.copy(restartOnChange = false).restartsOnChange)
    }

    @Test fun watchingBacksOffWhileNothingChanges() {
        var pace = WatchPace.MIN_MS
        val seen = mutableListOf<Long>()
        repeat(6) { pace = WatchPace.next(pace, changed = false, visible = true); seen += pace }
        assertEquals(listOf(1_500L, 2_250L, 3_375L, 5_000L, 5_000L, 5_000L), seen)
        // A change brings it straight back to every second; the background waits the longest, but still watches.
        assertEquals(WatchPace.MIN_MS, WatchPace.next(5_000L, changed = true, visible = true))
        assertEquals(WatchPace.MIN_MS, WatchPace.next(5_000L, changed = true, visible = false))
        assertEquals(WatchPace.MAX_MS, WatchPace.next(WatchPace.MIN_MS, changed = false, visible = false))
    }
}
