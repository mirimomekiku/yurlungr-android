package dev.yurlungr.app.runtime

import android.app.Instrumentation
import dev.yurlungr.app.projects.Git
import dev.yurlungr.app.projects.ProjectChecks
import dev.yurlungr.app.projects.ProjectSearch
import dev.yurlungr.app.projects.ProjectTasks
import dev.yurlungr.app.projects.StackDetector
import dev.yurlungr.app.projects.isStaged
import dev.yurlungr.app.web.LocalServers
import dev.yurlungr.app.web.WebQuickRun
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

/**
 * The 0.0.4 features against the real runtime on a device: the environment every process gets,
 * JDK detection, git stage/unstage/discard, ripgrep search, the process list and port probe,
 * stack/task/check detection, disk sizes and pending updates. Uses temporary folders only;
 * never the user's projects, settings or packages, and no screen interaction.
 */
internal object FeatureSmokeChecks {
    /** One line per check: "PASS name: detail" or "SKIP name: why". Throws on the first failure. */
    fun run(instrumentation: Instrumentation): List<String> = runBlocking {
        val context = instrumentation.targetContext
        val paths = RuntimePaths(context)
        check(paths.isBootstrapInstalled) { "Install the Linux runtime first" }
        RuntimeAssets.install(context, paths)
        val lines = ArrayList<String>()
        fun pass(name: String, detail: String = "") { lines += "PASS $name" + if (detail.isNotEmpty()) ": $detail" else "" }
        fun skip(name: String, why: String) { lines += "SKIP $name: $why" }
        suspend fun sh(script: String, cwd: File = paths.home) =
            CommandRunner.run(paths, listOf(paths.bash.path, "-c", script), cwd = cwd, timeoutMs = 60_000)

        // Environment: what agents and command terminals get.
        val env = RuntimeEnv.map(paths)
        val jdk = Jdks.current(paths.prefix)
        if (jdk == null) skip("JAVA_HOME", "no JDK installed")
        else {
            check(env["JAVA_HOME"] == jdk.home.path) { "JAVA_HOME ${env["JAVA_HOME"]} != ${jdk.home}" }
            val r = sh("readlink -f \"\$(command -v java)\"; readlink -f \"\$JAVA_HOME/bin/java\"")
            val (a, b) = r.output.trim().lines().let { it.firstOrNull() to it.getOrNull(1) }
            check(r.ok && a == b) { "java on PATH ($a) isn't JAVA_HOME's ($b)" }
            pass("JAVA_HOME", "Java ${jdk.version} at ${jdk.home.path}; installed: ${Jdks.installed(paths.prefix).map { it.version }}")
        }
        check(env["PATH"].orEmpty().split(':').containsAll(listOf(File(paths.home, ".local/bin").path, File(paths.home, ".cargo/bin").path))) { "User tool folders missing from PATH" }
        check(env["PATH"]!!.indexOf(paths.bin.path) < env["PATH"]!!.indexOf(File(paths.home, ".local/bin").path)) { "User folders come before \$PREFIX/bin" }
        check(env["PNPM_HOME"] != null) { "PNPM_HOME unset" }
        pass("PATH and tool homes")
        val profiled = sh("${RuntimeEnv.PROFILE_SNIPPET}; echo \"marker:\${JAVA_HOME:-none}\"")
        check(profiled.ok && profiled.output.trim().lines().last().startsWith("marker:")) { "profile.d loading: ${profiled.output}" }
        check(profiled.output.trim().lines().size == 1) { "profile.d printed output (would corrupt an agent's protocol): ${profiled.output}" }
        pass("profile.d loads silently")

        // Shims the agent notes promise.
        for (shim in listOf("yurlungr-open", "yurlungr-install", "yurlungr-notify")) check(File(paths.shimBin, shim).canExecute()) { "missing $shim" }
        pass("shims")

        val fixture = File(context.cacheDir, "feature-smoke-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            // Git: stage, unstage, discard (the Changes tab) on a scratch repository.
            val git = Git(paths)
            if (!File(paths.bin, "git").canExecute()) skip("git", "not installed")
            else {
                val repo = File(fixture, "repo").apply { mkdirs() }
                check(sh("git init -q && git -c user.name=t -c user.email=t@t commit -q --allow-empty -m init", repo).ok) { "git init" }
                File(repo, "a.txt").writeText("one\n")
                check(git.stage(repo, listOf("a.txt")).ok)
                check(git.status(repo).changes.single { it.path == "a.txt" }.isStaged) { "a.txt not staged" }
                check(git.unstage(repo, listOf("a.txt"), unborn = false).ok)
                check(!git.status(repo).changes.single { it.path == "a.txt" }.isStaged) { "a.txt still staged" }
                val change = git.status(repo).changes.single { it.path == "a.txt" }
                check(git.discard(repo, change).ok && !File(repo, "a.txt").exists()) { "discard of a new file should delete it" }
                File(repo, "b.txt").writeText("b\n")
                check(sh("git add b.txt && git -c user.name=t -c user.email=t@t commit -q -m b", repo).ok)
                File(repo, "b.txt").writeText("changed\n")
                check(git.discard(repo, git.status(repo).changes.single()).ok && File(repo, "b.txt").readText() == "b\n") { "discard of a modified file" }
                pass("git stage/unstage/discard")
            }

            // Checkpoints: snapshot, change, diff, restore (the shadow repository, not the project's git).
            if (!File(paths.bin, "git").canExecute()) skip("checkpoints", "git not installed")
            else {
                val proj0 = File(paths.home, "projects/.feature-smoke-${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    File(proj0, "a.txt").writeText("one\n")
                    val chat = "smoke-${UUID.randomUUID()}"
                    val turn = dev.yurlungr.app.chat.Checkpoints.begin(context, chat, proj0, "change things")
                    check(turn.before != null) { "no snapshot before" }
                    File(proj0, "a.txt").writeText("two\n"); File(proj0, "b.txt").writeText("new\n")
                    dev.yurlungr.app.chat.Checkpoints.end(context, chat, proj0, turn)
                    val done = dev.yurlungr.app.chat.Checkpoints.turns(context, chat).single()
                    check(done.filesChanged == 2) { "files changed: ${done.filesChanged}" }
                    check(dev.yurlungr.app.chat.Checkpoints.diff(context, chat, proj0, done.before!!, done.after!!).contains("+two")) { "diff" }
                    check(dev.yurlungr.app.chat.Checkpoints.restore(context, chat, proj0, done.before!!).isSuccess) { "restore" }
                    check(File(proj0, "a.txt").readText() == "one\n" && !File(proj0, "b.txt").exists()) { "restore didn't put files back" }
                    check(!File(proj0, ".git").exists()) { "touched the project's own git" }
                    dev.yurlungr.app.chat.Checkpoints.delete(context, chat)
                    pass("checkpoints", "snapshot, diff and restore")
                } finally { proj0.deleteRecursively() }
            }

            // Stacks, tasks and checks on a small Node + Python fixture.
            val proj = File(fixture, "proj").apply { mkdirs() }
            File(proj, "package.json").writeText("""{"scripts":{"dev":"vite"},"devDependencies":{"vite":"6","typescript":"5"}}""")
            File(proj, "pnpm-lock.yaml").writeText("")
            File(proj, "requirements.txt").writeText("flask\n")
            File(proj, "Makefile").writeText("build:\n\techo hi\n")
            val stacks = StackDetector.detect(proj)
            check(stacks.map { it.id }.containsAll(listOf("node", "python"))) { "stacks: ${stacks.map { it.id }}" }
            val tasks = ProjectTasks.detect(proj, stacks).map { it.command }
            check("pnpm run dev" in tasks && "make build" in tasks) { "tasks: $tasks" }
            check(ProjectChecks.checkCommands(stacks).any { it.command.contains("tsc") }) { "no tsc check" }
            pass("stacks, scripts and checks", "${stacks.size} stacks, ${tasks.size} scripts; missing runtimes: ${stacks.flatMap { it.missingPackages(paths.prefix) }}")

            // ripgrep search (Files → Search in files).
            val rg = File(paths.bin, "rg")
            if (!rg.canExecute()) skip("search", "ripgrep not installed")
            else {
                File(proj, "src").mkdirs()
                File(proj, "src/app.ts").writeText("const x = 1\nexport function needleHere() {}\n")
                val r = CommandRunner.run(paths, ProjectSearch.args(rg, "needleHere", regex = false), cwd = proj, forParsing = true, timeoutMs = 20_000)
                val hits = ProjectSearch.parse(r.output)
                check(hits.singleOrNull()?.let { it.path == "src/app.ts" && it.line == 2 } == true) { "search: $hits ${r.errorOutput}" }
                pass("search in files")
            }

            // Processes and ports (Servers).
            val ps = CommandRunner.run(paths, listOf(File(paths.bin, "ps").path, "-eo", "pid=,ppid=,etimes=,rss=,args="), forParsing = true, timeoutMs = 10_000)
            if (!ps.ok) skip("processes", "ps: ${ps.errorOutput.take(120)}")
            else pass("processes", "${LocalServers.parsePs(ps.output).size} visible")
            ServerSocket(5173, 1, InetAddress.getByName("127.0.0.1")).use { listening ->
                check(5173 in LocalServers.probe(emptyList())) { "port probe missed a listener on 5173" }
                check(WebQuickRun.freePort(5173) != 5173) { "free port picked a busy one" }
                pass("port probe and free port")
            }

            // Disk and updates (Development environment).
            val sizes = Storage.installedSizes(paths.prefix)
            check(sizes.isNotEmpty()) { "dpkg status unreadable" }
            val du = CommandRunner.shell(paths, Storage.duScript(listOf(paths.home)), timeoutMs = 120_000)
            check(Storage.parseDu(du.output).isNotEmpty()) { "du: ${du.output}" }
            val updates = CommandRunner.shell(paths, Storage.UPGRADABLE_SCRIPT, timeoutMs = 60_000).output.trim().lines().lastOrNull()?.toIntOrNull()
            check(updates != null) { "couldn't count updates" }
            pass("disk and updates", "${sizes.size} packages installed, $updates updates")

            // Gradle toolchain properties (on a copy, never the user's file).
            val jdks = Jdks.installed(paths.prefix)
            if (jdks.isEmpty()) skip("gradle toolchains", "no JDK")
            else {
                val text = Jdks.withGradleToolchains("org.gradle.jvmargs=-Xmx2g\n", jdks)
                check(jdks.all { text.contains(it.home.path) }) { text }
                pass("gradle toolchains", jdks.joinToString { it.version.toString() })
            }
        } finally {
            fixture.deleteRecursively()
        }
        lines
    }
}
