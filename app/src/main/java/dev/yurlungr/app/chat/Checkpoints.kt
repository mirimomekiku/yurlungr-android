package dev.yurlungr.app.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.yurlungr.app.runtime.CommandResult
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** One prompt and what it changed: snapshots of the project before and after. */
data class ChatTurn(
    val index: Int,
    val prompt: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val before: String? = null,
    val after: String? = null,
    val filesChanged: Int? = null,
    /** A restore, recorded like a turn: [before] is the state it replaced, so restoring that undoes it. */
    val restore: Boolean = false,
) {
    val millis: Long get() = (endedAt ?: startedAt) - startedAt
}

/**
 * Per-turn checkpoints of a chat's project, and its turn statistics.
 *
 * Snapshots go into a shadow git repository per project, under the app's files: never the
 * project's own .git, index or refs, and they work in folders that aren't repositories. The
 * project's .gitignore files still apply, and build and dependency folders are always left out.
 * Restoring puts the files back as they were before a turn (and takes a snapshot first).
 */
object Checkpoints {
    private val EXCLUDES = listOf("node_modules/", "build/", "dist/", ".gradle/", "target/", ".venv/", ".yurlungr/", ".git/", "__pycache__/", ".next/", ".dart_tool/")

    private fun root(context: Context) = File(context.filesDir, "checkpoints")
    private fun turnsFile(context: Context, chatId: String) = File(root(context), "$chatId.json")
    private fun gitDir(context: Context, cwd: File): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(cwd.canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        return File(root(context), "repo-$hash.git")
    }

    fun turns(context: Context, chatId: String): List<ChatTurn> =
        runCatching { parseTurns(turnsFile(context, chatId).readText()) }.getOrNull().orEmpty()

    /**
     * The turns to add to or update. A file that exists but can't be read as a whole is moved
     * aside to `.bad` first, so the next save doesn't replace the history with one turn.
     */
    internal fun turnsForUpdate(f: File): List<ChatTurn> {
        if (!f.exists()) return emptyList()
        runCatching { parseTurns(f.readText()) }.getOrNull()?.let { return it }
        val bad = File(f.path + ".bad")
        bad.delete()
        if (!f.renameTo(bad)) runCatching { f.copyTo(bad, overwrite = true) }
        return emptyList()
    }

    private fun turnsForUpdate(context: Context, chatId: String) = turnsForUpdate(turnsFile(context, chatId))

    /** A turns file's entries (malformed ones skipped), or null if it isn't a JSON array at all. */
    internal fun parseTurns(text: String): List<ChatTurn>? {
        val array = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return null
        return array.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun p(k: String) = o[k] as? JsonPrimitive
            fun s(k: String) = p(k)?.contentOrNull
            fun l(k: String) = p(k)?.longOrNull
            val index = p("index")?.intOrNull ?: return@mapNotNull null
            ChatTurn(index, s("prompt").orEmpty(), l("startedAt") ?: 0, l("endedAt"),
                s("before"), s("after"), p("filesChanged")?.intOrNull, s("restore") == "true")
        }
    }

    private fun encode(turns: List<ChatTurn>): String = JsonArray(turns.map { t ->
        buildJsonObject {
            put("index", t.index); put("prompt", t.prompt); put("startedAt", t.startedAt)
            t.endedAt?.let { put("endedAt", it) }; t.before?.let { put("before", it) }; t.after?.let { put("after", it) }
            t.filesChanged?.let { put("filesChanged", it) }
            if (t.restore) put("restore", true)
        }
    }).toString()

    private fun save(context: Context, chatId: String, turns: List<ChatTurn>) = runCatching {
        val f = turnsFile(context, chatId)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(encode(turns))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    /** Restores run here, not in a sheet's scope: closing the sheet mustn't stop one halfway. */
    val scope by lazy { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate) }

    /** A restore is running (one at a time). For the UI; [restoreBusy] is what decides. */
    var restoring by androidx.compose.runtime.mutableStateOf(false)
        private set
    private val restoreBusy = MutableStateFlow(false)

    /** Returns once no restore is running: a prompt mustn't reach the agent while files are being put back. */
    suspend fun awaitRestore() {
        restoreBusy.first { !it }
    }

    /**
     * One lock per shadow repository: another chat's snapshot (write-tree, commit-tree, then
     * update-ref) mustn't interleave with a restore, or with a delete's ref removal and gc.
     */
    private val repoLocks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(repo: File): Mutex = repoLocks.computeIfAbsent(repo.path) { Mutex() }

    /**
     * Forgets a chat's checkpoints: its turns, its index, and its snapshots in every project's
     * shadow repository (then gc drops unreferenced objects once past git's grace period), so
     * storage doesn't only grow.
     */
    suspend fun delete(context: Context, chatId: String) = withContext(Dispatchers.IO) {
        turnsFile(context, chatId).delete()
        File(root(context), "$chatId.index").delete()
        val paths = RuntimePaths(context)
        val git = File(paths.bin, "git").takeIf { it.canExecute() } ?: return@withContext
        for (repo in root(context).listFiles { f -> f.isDirectory && f.name.startsWith("repo-") }.orEmpty()) {
            val refs = CommandRunner.run(paths, listOf(git.path, "--git-dir=${repo.path}", "for-each-ref", "--format=%(refname)", "refs/chats/$chatId/"),
                forParsing = true, timeoutMs = 30_000).output.lines().filter { it.startsWith("refs/chats/") }
            if (refs.isEmpty()) continue
            lockFor(repo).withLock {
                for (ref in refs) CommandRunner.run(paths, listOf(git.path, "--git-dir=${repo.path}", "update-ref", "-d", ref), timeoutMs = 30_000)
                // Not --prune=now: objects another chat has written but not yet referenced must survive.
                CommandRunner.run(paths, listOf(git.path, "--git-dir=${repo.path}", "gc", "--quiet"), timeoutMs = 300_000)
            }
        }
    }

    /**
     * Turns cut off by the app being killed (no end recorded): marked ended, so they stop reading
     * "running" and offer "Restore to before", the undo most wanted after a crash.
     */
    fun closeInterrupted(context: Context, chatId: String) {
        val turns = turnsForUpdate(context, chatId)
        if (turns.none { it.endedAt == null }) return
        save(context, chatId, turns.map { if (it.endedAt == null) it.copy(endedAt = it.startedAt) else it })
    }

    /** Whether a folder is worth snapshotting: a project, not the whole home folder. */
    fun enabledFor(paths: RuntimePaths, cwd: File): Boolean =
        cwd.isDirectory && cwd.canonicalPath != paths.home.canonicalPath && cwd.canonicalPath.startsWith(paths.home.canonicalPath)

    private suspend fun git(context: Context, chatId: String, cwd: File, vararg args: String, timeoutMs: Long = 90_000): CommandResult {
        val paths = RuntimePaths(context)
        val dir = gitDir(context, cwd)
        return CommandRunner.run(
            // quotePath off and literal pathspecs: names (non-ASCII, quotes, `*`) come and go as they are.
            paths, listOf(File(paths.bin, "git").path, "-c", "core.quotePath=false", "--literal-pathspecs", "--git-dir=${dir.path}", "--work-tree=${cwd.path}", *args),
            cwd = cwd, timeoutMs = timeoutMs, forParsing = true,
            // One index per chat, so two chats in one project don't fight over it.
            extraEnv = mapOf("GIT_INDEX_FILE" to File(root(context), "$chatId.index").path, "GIT_AUTHOR_NAME" to "Yurlungr", "GIT_AUTHOR_EMAIL" to "checkpoints@yurlungr",
                "GIT_COMMITTER_NAME" to "Yurlungr", "GIT_COMMITTER_EMAIL" to "checkpoints@yurlungr"),
        )
    }

    /** A commit of the project's files right now (into the shadow repository), or null. */
    suspend fun snapshot(context: Context, chatId: String, cwd: File, label: String): String? =
        lockFor(gitDir(context, cwd)).withLock { snapshotLocked(context, chatId, cwd, label) }

    /** [snapshot], for a caller already holding the repository's lock. */
    private suspend fun snapshotLocked(context: Context, chatId: String, cwd: File, label: String): String? = withContext(Dispatchers.IO) {
        val paths = RuntimePaths(context)
        if (!enabledFor(paths, cwd) || !File(paths.bin, "git").canExecute()) return@withContext null
        val dir = gitDir(context, cwd)
        if (!File(dir, "HEAD").exists()) {
            dir.mkdirs()
            val init = CommandRunner.run(paths, listOf(File(paths.bin, "git").path, "init", "--quiet", "--bare", dir.path), timeoutMs = 30_000)
            if (!init.ok) return@withContext null
            File(dir, "info").mkdirs()
            File(dir, "info/exclude").writeText(EXCLUDES.joinToString("\n") + "\n")
        }
        if (!git(context, chatId, cwd, "add", "-A", "--", ".").ok) return@withContext null
        val tree = git(context, chatId, cwd, "write-tree").output.trim().takeIf { it.length == 40 } ?: return@withContext null
        val commit = git(context, chatId, cwd, "commit-tree", tree, "-m", label).output.trim().takeIf { it.length == 40 } ?: return@withContext null
        // A ref keeps it from being pruned.
        git(context, chatId, cwd, "update-ref", "refs/chats/$chatId/${System.currentTimeMillis()}", commit)
        commit
    }

    /** Starts a turn: its snapshot before the agent works. */
    suspend fun begin(context: Context, chatId: String, cwd: File, prompt: String): ChatTurn {
        val before = snapshot(context, chatId, cwd, "before: ${prompt.take(60)}")
        val turns = withContext(Dispatchers.IO) { turnsForUpdate(context, chatId) }
        val turn = ChatTurn(turns.size + 1, prompt.take(200), System.currentTimeMillis(), before = before)
        save(context, chatId, turns + turn)
        return turn
    }

    /** Ends a turn: its snapshot after, and how many files changed. */
    suspend fun end(context: Context, chatId: String, cwd: File, turn: ChatTurn) {
        val after = snapshot(context, chatId, cwd, "after: ${turn.prompt.take(60)}")
        val changed = if (turn.before != null && after != null) changedFiles(context, chatId, cwd, turn.before, after).size else null
        val done = turn.copy(endedAt = System.currentTimeMillis(), after = after, filesChanged = changed)
        save(context, chatId, turnsForUpdate(context, chatId).map { if (it.index == turn.index && it.startedAt == turn.startedAt) done else it })
    }

    suspend fun changedFiles(context: Context, chatId: String, cwd: File, from: String, to: String): List<String> =
        git(context, chatId, cwd, "diff", "-z", "--name-only", from, to).output.split('\u0000').filter { it.isNotEmpty() }

    /** Files that differ between two snapshots with their status letter (A added, M modified, D deleted…). */
    suspend fun changedFilesWithStatus(context: Context, chatId: String, cwd: File, from: String, to: String): List<Pair<Char, String>> =
        parseNameStatusZ(git(context, chatId, cwd, "diff", "-z", "--name-status", from, to).output)

    /** `diff -z --name-status` output: (status letter, path) pairs. */
    internal fun parseNameStatusZ(out: String): List<Pair<Char, String>> {
        val parts = out.split('\u0000')
        val result = ArrayList<Pair<Char, String>>()
        var i = 0
        while (i + 1 < parts.size) {
            val status = parts[i]
            if (status.isEmpty()) { i++; continue }
            result += status[0] to parts[i + 1]
            i += 2
        }
        return result
    }

    /** The unified diff of what a turn changed. */
    suspend fun diff(context: Context, chatId: String, cwd: File, from: String, to: String): String =
        git(context, chatId, cwd, "diff", "--no-color", "--no-ext-diff", from, to).output.ifBlank { "(no changes)" }

    /**
     * Puts the project's files back as they were at [target]: changed and deleted files are
     * restored, files added since are removed. A snapshot is taken first, so this can be undone.
     */
    suspend fun restore(context: Context, chatId: String, cwd: File, target: String, label: String = "Restored"): Result<String> {
        // Claimed before any work (and before leaving the caller's thread): two restores can't both start.
        if (!restoreBusy.compareAndSet(false, true)) return Result.failure(IllegalStateException("Another restore is running"))
        try {
            withContext(Dispatchers.Main) { restoring = true }
            return lockFor(gitDir(context, cwd)).withLock { withContext(Dispatchers.IO) { restoreLocked(context, chatId, cwd, target, label) } }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { restoring = false }
            restoreBusy.value = false
        }
    }

    private suspend fun restoreLocked(context: Context, chatId: String, cwd: File, target: String, label: String): Result<String> {
        val now = snapshotLocked(context, chatId, cwd, "before restoring ${target.take(7)}")
            ?: return Result.failure(IllegalStateException("Couldn't take a safety snapshot first"))
        val status = parseNameStatusZ(git(context, chatId, cwd, "diff", "-z", "--name-status", "--no-renames", target, now).output)
        val added = status.filter { it.first == 'A' }.map { it.second }
        val restoreList = status.filter { it.first != 'A' }.map { it.second }
        for (path in added) File(cwd, path).takeIf { it.canonicalPath.startsWith(cwd.canonicalPath + File.separator) }?.delete()
        if (restoreList.isNotEmpty()) {
            // Paths through a file, NUL-separated: no argument-length limit, any characters.
            val list = File(root(context), "$chatId.restore").apply { writeText(restoreList.joinToString("\u0000")) }
            val r = try {
                git(context, chatId, cwd, "checkout", target, "--pathspec-from-file=${list.path}", "--pathspec-file-nul")
            } finally { list.delete() }
            if (!r.ok) return Result.failure(IllegalStateException(r.errorOutput.ifBlank { r.output }.lines().firstOrNull { it.isNotBlank() } ?: "git checkout failed"))
        }
        // Recorded like a turn whose "before" is the state just replaced: restoring that undoes this.
        val turns = turnsForUpdate(context, chatId)
        val at = System.currentTimeMillis()
        save(context, chatId, turns + ChatTurn(turns.size + 1, label, at, at, before = now, after = target, filesChanged = status.size, restore = true))
        return Result.success(now)
    }

    /** Turns, time and the agent's last reported usage, for one chat. */
    data class Stats(val turns: Int, val millis: Long, val filesChanged: Int)

    fun stats(context: Context, chatId: String): Stats = turns(context, chatId).let { t ->
        Stats(t.size, t.sumOf { it.millis }, t.sumOf { it.filesChanged ?: 0 })
    }
}
