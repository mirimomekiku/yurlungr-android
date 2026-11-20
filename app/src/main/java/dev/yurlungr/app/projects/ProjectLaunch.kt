package dev.yurlungr.app.projects

import android.content.Context
import android.widget.Toast
import androidx.core.content.edit
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.projects.Git.Companion.shellQuote
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.web.CustomServers
import dev.yurlungr.app.web.ProjectDetector
import dev.yurlungr.app.web.WebQuickRun
import dev.yurlungr.app.web.WebRunSessions
import dev.yurlungr.app.web.WebRunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/** What a new project or a clone needs to tell the app's screens. */
internal class LaunchCallbacks(
    val openProject: (File) -> Unit,
    val runInTerminal: (command: String, name: String) -> Unit,
    val preview: (url: String, title: String) -> Unit,
)

/**
 * Chains the existing flows after creating or cloning a project: a terminal command (clone,
 * dependency setup) whose end the app learns from a marker file, then the project's Run, then the
 * in-app preview once the server answers. Each step is the same one the user could tap.
 */
internal object ProjectLaunch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = HashMap<String, Job>()
    private const val PREFS = "project_launch"
    private const val RUN_AFTER_CLONE = "run_after_clone"
    /** Setups download a lot on slow phones; past this the chain gives up quietly (Run still works by hand). */
    private const val COMMAND_WAIT_MS = 3 * 60 * 60_000L
    private const val RUN_WAIT_MS = 20 * 60_000L

    fun runAfterClone(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(RUN_AFTER_CLONE, true)
    fun setRunAfterClone(context: Context, on: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(RUN_AFTER_CLONE, on) }

    /**
     * [command], then its exit status written to [marker] (atomically, via a temp file). The
     * terminal still sees the command's own status, for its "Command failed" line.
     */
    internal fun withExitMarker(command: String, marker: File): String {
        val tmp = shellQuote(marker.path + ".tmp")
        return "( $command ); _yl_rc=\$?; mkdir -p ${shellQuote(marker.parent ?: ".")} && printf '%s' \"\$_yl_rc\" > $tmp && mv -f $tmp ${shellQuote(marker.path)}; (exit \$_yl_rc)"
    }

    /** Status written by [withExitMarker]; null while the command runs (or if the file is unreadable). */
    internal fun readExit(marker: File): Int? = runCatching { marker.takeIf { it.isFile }?.readText()?.trim()?.toInt() }.getOrNull()

    /**
     * Writes [template]'s files into the new folder [dir] (which must not exist), initializes Git
     * and saves its run settings. Returns the setup command to run next, or null when there's none.
     */
    suspend fun create(context: Context, git: Git, dir: File, template: ProjectTemplate, initGit: Boolean): String? {
        val names = TemplateNames.of(dir.name, secret = "django-insecure-" + randomHex(24))
        withContext(Dispatchers.IO) {
            check(dir.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Couldn't open the projects folder." }
            check(!dir.exists()) { "A project with this name already exists." }
            check(dir.mkdir()) { "Couldn't create the folder. Check free storage and try again." }
            // Made here, from a template: trusted, whatever an earlier folder of this name was.
            ProjectTrust.forget(context, dir)
            if (!template.isEmpty) for ((path, text) in Templates.allFiles(template, names)) {
                val file = File(dir, path)
                check(file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "Bad template path: $path" }
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
        }
        if (initGit) {
            val result = git.init(dir)
            check(result.ok) { "Folder created, but Git initialization failed. ${result.output.trim().takeLast(240)}" }
        }
        if (template.isEmpty) return null
        template.server?.let { s ->
            withContext(Dispatchers.IO) { CustomServers.register(context, dir, s.framework, s.command, 8000, s.startupTimeoutSeconds) }
        }
        val stacks = withContext(Dispatchers.IO) { runCatching { StackDetector.detect(dir) }.getOrDefault(emptyList()) }
        return Templates.setupCommand(template, dir, names, stacks)
    }

    /** Opens [dir], runs [setupCommand] in a terminal (if any), then starts the project and shows its preview. */
    fun setupThenRun(context: Context, dir: File, setupCommand: String?, callbacks: LaunchCallbacks) {
        val app = context.applicationContext
        // This chain installs: automatic setup mustn't start the same install in a second terminal.
        SetupBannerPrefs.startedNow(dir)
        callbacks.openProject(dir)
        launch(dir) {
            if (setupCommand != null) {
                val ok = runAndWait(app, setupCommand, "Set up ${dir.name}", callbacks)
                if (ok != true) {
                    if (ok == false) toast(app, "Setup didn't finish, so ${dir.name} wasn't started. Check the terminal, then tap Run.")
                    return@launch
                }
            }
            run(app, dir, callbacks)
        }
    }

    /**
     * Clones someone else's repository: the folder is marked untrusted ([ProjectTrust]) before
     * anything runs, and nothing is installed or started. With [openAfter], the project opens
     * once cloned, where its banner explains what's held back. A clone that left no folder
     * clears the mark again, so a later project of that name starts fresh.
     */
    fun cloneUntrusted(context: Context, dir: File, command: String, callbacks: LaunchCallbacks, openAfter: Boolean) {
        val app = context.applicationContext
        ProjectTrust.markUntrusted(app, dir)
        launch(dir) {
            val ok = runAndWait(app, command, "Clone ${dir.name}", callbacks)
            if (!withContext(Dispatchers.IO) { dir.isDirectory }) {
                ProjectTrust.forget(app, dir)
                return@launch
            }
            if (ok == true && openAfter) {
                callbacks.openProject(dir)
                toast(app, "${dir.name} isn't your repository, so nothing was installed or started. Look through it, then trust it from the project screen.")
            }
        }
    }

    /** Runs the clone [command] for [dir], then opens it, installs what it needs and runs it if it's a web project. */
    fun cloneThenRun(context: Context, dir: File, command: String, callbacks: LaunchCallbacks) {
        val app = context.applicationContext
        // The user's own repository: a stale mark from an earlier folder of this name doesn't apply.
        ProjectTrust.forget(app, dir)
        launch(dir) {
            if (runAndWait(app, command, "Clone ${dir.name}", callbacks) != true || !withContext(Dispatchers.IO) { dir.isDirectory }) return@launch
            val prefix = RuntimePaths(app).prefix
            val needs = withContext(Dispatchers.IO) {
                runCatching { StackDetector.detect(dir).filter { it.needsSetup(prefix) } }.getOrDefault(emptyList())
            }
            SetupBannerPrefs.startedNow(dir)
            callbacks.openProject(dir)
            if (needs.isNotEmpty()) {
                SetupBannerPrefs.markAutoRan(app, dir, autoSetupSignature(needs))
                val ok = runAndWait(app, installCommand(dir, needs), "Set up ${dir.name}", callbacks)
                if (ok != true) {
                    if (ok == false) toast(app, "Setup didn't finish. Check the terminal; the project is open.")
                    return@launch
                }
            }
            run(app, dir, callbacks)
        }
    }

    private fun launch(dir: File, block: suspend CoroutineScope.() -> Unit) {
        val key = dir.absolutePath
        jobs.remove(key)?.cancel()
        jobs[key] = scope.launch(block = block).also { job -> job.invokeOnCompletion { scope.launch { if (jobs[key] === job) jobs.remove(key) } } }
    }

    /** True: exited 0. False: failed. Null: no result in time (closed terminal, never started). */
    private suspend fun runAndWait(context: Context, command: String, name: String, callbacks: LaunchCallbacks): Boolean? {
        val marker = File(context.cacheDir, "project-launch/${UUID.randomUUID()}.exit")
        callbacks.runInTerminal(withExitMarker(command, marker), name)
        val deadline = System.currentTimeMillis() + COMMAND_WAIT_MS
        try {
            while (System.currentTimeMillis() < deadline) {
                withContext(Dispatchers.IO) { readExit(marker) }?.let { return it == 0 }
                delay(1_500)
            }
            return null
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                marker.delete()
                File(marker.path + ".tmp").delete()
            }
        }
    }

    /** The existing Run, if the project is a web or dev-server project; the preview once it answers. */
    private suspend fun run(context: Context, dir: File, callbacks: LaunchCallbacks) {
        val projects = withContext(Dispatchers.IO) {
            runCatching { CustomServers.withSaved(context, dir, ProjectDetector.detect(dir)).web }.getOrDefault(emptyList())
        }
        if (projects.isEmpty()) return // not something Run can start: the project is open, that's all
        WebQuickRun.start(context, dir, projects, openBrowser = false)?.let { why ->
            toast(context, "$why Open Run options in ${dir.name}.")
            return
        }
        val deadline = System.currentTimeMillis() + RUN_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val run = WebRunSessions.get(dir) ?: return
            when {
                run.state == WebRunState.Running -> {
                    if (ChatSessions.appVisible) callbacks.preview(run.url, "${run.config.project.framework} · ${dir.name}")
                    return
                }
                run.finalized || !run.state.active -> return // failed or stopped: the project's server card says why
            }
            delay(700)
        }
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
