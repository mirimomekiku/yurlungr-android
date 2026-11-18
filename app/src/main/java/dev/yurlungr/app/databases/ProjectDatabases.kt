package dev.yurlungr.app.databases

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import dev.yurlungr.app.editor.FileSave
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.runtime.RuntimeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** A project's database server as last seen. */
data class DbServer(val project: File, val kind: DbKind, val port: Int, val database: String, val pid: Int) {
    val key: String get() = DbNaming.projectId(project.path) + "/" + kind.id
}

/**
 * The database servers projects run (PostgreSQL, Redis, MariaDB), started by `project-db.sh` as
 * daemons. Data lives under `~/.local/share/yurlungr/databases/<project id>/`, outside the repo.
 * A running server keeps the runtime service in the foreground (so it outlives the UI), and the
 * notification's Exit stops them. Whether a server runs is read from its pid file, checked against
 * `/proc`, so a reused pid is never mistaken for it (or signalled).
 */
object ProjectDatabases {
    /**
     * Starts and stops run here, not in a screen's scope: leaving the screen would cancel the
     * command, and a cancelled command's process tree is killed (a half-started server with it).
     */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Running servers by [DbServer.key]. Read by the Servers screen and the runtime service. */
    val running = mutableStateMapOf<String, DbServer>()

    /** "Starting…" / "Stopping…" per [DbServer.key]-style key while a script runs. */
    val busy = mutableStateMapOf<String, String>()

    val anyRunning: Boolean get() = running.isNotEmpty()

    fun root(paths: RuntimePaths) = File(paths.home, ".local/share/yurlungr/databases")

    fun folder(paths: RuntimePaths, project: File) = File(root(paths), DbNaming.projectId(canonical(project).path))

    /** Short, so the Unix socket paths stay under the 107-byte limit. */
    private fun socketFolder(paths: RuntimePaths, project: File) = File(paths.tmp, "ydb-" + DbNaming.hash(canonical(project).path).take(10))

    private fun canonical(project: File) = runCatching { project.canonicalFile }.getOrDefault(project.absoluteFile)

    fun key(project: File, kind: DbKind) = DbNaming.projectId(canonical(project).path) + "/" + kind.id

    fun databaseName(project: File) = DbNaming.databaseName(canonical(project).name)

    /** Whether the Databases pack provides [kind]'s programs. */
    fun installed(paths: RuntimePaths, kind: DbKind): Boolean =
        kind.programs.all { group -> group.any { File(paths.bin, it).canExecute() } }

    /** The project's saved settings, created (with a new password) the first time. Call off the main thread. */
    @Synchronized
    fun config(paths: RuntimePaths, project: File): DbProjectConfig {
        val file = File(folder(paths, project), "db.properties")
        runCatching { DbProjectConfig.parse(file.readText()) }.getOrNull()?.let { return it }
        val fresh = DbProjectConfig(canonical(project).path, DbNaming.newPassword())
        save(file, fresh)
        return fresh
    }

    private fun save(file: File, config: DbProjectConfig) {
        file.parentFile?.mkdirs()
        FileSave.writeAtomically(file, config.format())
    }

    /** Ports other projects have claimed, so two projects never get the same one. */
    private fun reservedPorts(paths: RuntimePaths, except: File): Set<Int> =
        root(paths).listFiles().orEmpty().filter { it.isDirectory && it != except }.flatMap { dir ->
            runCatching { DbProjectConfig.parse(File(dir, "db.properties").readText())?.ports?.values.orEmpty() }.getOrDefault(emptyList())
        }.toSet()

    private fun portOpen(port: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250); true } }.getOrDefault(false)

    /** The server's pid, when its pid file names a live process of the right program. Call off the main thread. */
    private fun livePid(paths: RuntimePaths, project: File, kind: DbKind): Int? {
        val pid = runCatching { DbNaming.parsePid(File(folder(paths, project), kind.pidFile).readText()) }.getOrNull() ?: return null
        val cmdline = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull() ?: return null
        return pid.takeIf { DbNaming.cmdlineMatches(cmdline, kind.processNames) }
    }

    /** Re-reads every kind for [project] and updates [running]. */
    suspend fun refresh(paths: RuntimePaths, project: File): Map<DbKind, DbServer?> = withContext(Dispatchers.IO) {
        val dir = folder(paths, project)
        val config = if (File(dir, "db.properties").isFile) runCatching { config(paths, project) }.getOrNull() else null
        DbKind.entries.associateWith { kind ->
            val port = config?.port(kind) ?: return@associateWith null
            livePid(paths, project, kind)?.let { DbServer(canonical(project), kind, port, databaseName(project), it) }
        }
    }.also { found ->
        for ((kind, server) in found) {
            if (server != null) running[server.key] = server else running.remove(key(project, kind))
        }
    }

    /** Re-reads every project that has database data (for the Servers screen). */
    suspend fun refreshAll(paths: RuntimePaths) {
        val projects = withContext(Dispatchers.IO) {
            root(paths).listFiles().orEmpty().mapNotNull { dir ->
                runCatching { DbProjectConfig.parse(File(dir, "db.properties").readText())?.projectPath }.getOrNull()
            }.map(::File)
        }
        val keep = projects.flatMap { p -> DbKind.entries.map { key(p, it) } }.toSet()
        running.keys.filter { it !in keep }.forEach { running.remove(it) }
        for (p in projects) refresh(paths, p)
    }

    /**
     * Starts [kind] for [project]: picks (and remembers) a free port on first use, or a new one if
     * something else took the saved port. Returns a line for the user.
     */
    suspend fun start(context: Context, project: File, kind: DbKind): Result<String> {
        val app = context.applicationContext
        val paths = RuntimePaths(app)
        val k = key(project, kind)
        if (busy.containsKey(k)) return Result.failure(IllegalStateException("${kind.title} is busy"))
        busy[k] = "Starting…"
        try {
            val outcome = withContext(Dispatchers.IO) {
                if (!installed(paths, kind)) return@withContext Result.failure(IllegalStateException("${kind.title} isn't installed. Install Databases in System checks."))
                var config = config(paths, project)
                val dir = folder(paths, project)
                var note = ""
                val saved = config.port(kind)
                if (livePid(paths, project, kind) == null && (saved == null || portOpen(saved))) {
                    val port = DbNaming.pickPort(kind.basePort, reservedPorts(paths, dir) + config.ports.filterKeys { it != kind }.values, ::portOpen)
                        ?: return@withContext Result.failure(IllegalStateException("No free port near ${kind.basePort}"))
                    if (saved != null) note = " Port $saved was taken, so it moved to $port: update .env."
                    config = config.withPort(kind, port)
                    save(File(dir, "db.properties"), config)
                }
                val port = config.port(kind)!!
                val script = File(paths.libexec, "project-db.sh")
                val r = CommandRunner.run(
                    paths,
                    listOf(paths.bash.path, script.path, "start", kind.id, dir.path, socketFolder(paths, project).path, port.toString(), databaseName(project)),
                    cwd = paths.home,
                    timeoutMs = 240_000,
                    extraEnv = mapOf("YURLUNGR_DB_PASSWORD" to config.password),
                )
                if (r.ok) Result.success("${kind.title} is running on port $port.$note")
                else Result.failure(IllegalStateException(r.describe("Starting ${kind.title}")))
            }
            refresh(paths, project)
            return outcome
        } finally {
            busy.remove(k)
            RuntimeService.refresh(app)
        }
    }

    suspend fun stop(context: Context, project: File, kind: DbKind): Result<String> {
        val app = context.applicationContext
        val paths = RuntimePaths(app)
        val k = key(project, kind)
        if (busy.containsKey(k)) return Result.failure(IllegalStateException("${kind.title} is busy"))
        busy[k] = "Stopping…"
        try {
            val r = CommandRunner.run(
                paths,
                listOf(paths.bash.path, File(paths.libexec, "project-db.sh").path, "stop", kind.id, folder(paths, project).path),
                timeoutMs = 60_000,
            )
            refresh(paths, project)
            return if (r.ok) Result.success("${kind.title} stopped.") else Result.failure(IllegalStateException(r.describe("Stopping ${kind.title}")))
        } finally {
            busy.remove(k)
            RuntimeService.refresh(app)
        }
    }

    /** The notification's Exit: stops every running server, cleanly, in the background. */
    fun stopAll(context: Context) {
        val app = context.applicationContext
        val servers = running.values.toList()
        // Forgotten first, so the service doesn't come back to the foreground while they stop
        // (a server that fails to stop is found again by the stop's own refresh).
        running.clear()
        for (server in servers) scope.launch { stop(app, server.project, server.kind) }
    }

    /** The full connection URL (password included) for copying or writing to `.env`. Call off the main thread. */
    fun url(paths: RuntimePaths, server: DbServer): String =
        server.kind.url(server.port, config(paths, server.project).password, server.database)
}
