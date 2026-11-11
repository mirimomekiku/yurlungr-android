package dev.yurlungr.app.chat

import android.content.Context
import dev.yurlungr.app.runtime.CommandResult
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
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

/** One prompt and what it changed: snapshots of the project before and after. */
data class ChatTurn(
    val index: Int,
    val prompt: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val before: String? = null,
    val after: String? = null,
    val filesChanged: Int? = null,
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

    fun turns(context: Context, chatId: String): List<ChatTurn> = runCatching {
        (Json.parseToJsonElement(turnsFile(context, chatId).readText()) as JsonArray).map { e ->
            val o = e as JsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            fun l(k: String) = o[k]?.jsonPrimitive?.longOrNull
            ChatTurn(o["index"]!!.jsonPrimitive.intOrNull ?: 0, s("prompt").orEmpty(), l("startedAt") ?: 0, l("endedAt"),
                s("before"), s("after"), o["filesChanged"]?.jsonPrimitive?.intOrNull)
        }
    }.getOrDefault(emptyList())

    private fun encode(turns: List<ChatTurn>): String = JsonArray(turns.map { t ->
        buildJsonObject {
            put("index", t.index); put("prompt", t.prompt); put("startedAt", t.startedAt)
            t.endedAt?.let { put("endedAt", it) }; t.before?.let { put("before", it) }; t.after?.let { put("after", it) }
            t.filesChanged?.let { put("filesChanged", it) }
        }
    }).toString()

    private fun save(context: Context, chatId: String, turns: List<ChatTurn>) = runCatching {
        val f = turnsFile(context, chatId)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(encode(turns))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    fun delete(context: Context, chatId: String) { turnsFile(context, chatId).delete(); File(root(context), "$chatId.index").delete() }

    /** Whether a folder is worth snapshotting: a project, not the whole home folder. */
    fun enabledFor(paths: RuntimePaths, cwd: File): Boolean =
        cwd.isDirectory && cwd.canonicalPath != paths.home.canonicalPath && cwd.canonicalPath.startsWith(paths.home.canonicalPath)

    private suspend fun git(context: Context, chatId: String, cwd: File, vararg args: String, timeoutMs: Long = 90_000): CommandResult {
        val paths = RuntimePaths(context)
        val dir = gitDir(context, cwd)
        return CommandRunner.run(
            paths, listOf(File(paths.bin, "git").path, "--git-dir=${dir.path}", "--work-tree=${cwd.path}", *args),
            cwd = cwd, timeoutMs = timeoutMs, forParsing = true,
            // One index per chat, so two chats in one project don't fight over it.
            extraEnv = mapOf("GIT_INDEX_FILE" to File(root(context), "$chatId.index").path, "GIT_AUTHOR_NAME" to "Yurlungr", "GIT_AUTHOR_EMAIL" to "checkpoints@yurlungr",
                "GIT_COMMITTER_NAME" to "Yurlungr", "GIT_COMMITTER_EMAIL" to "checkpoints@yurlungr"),
        )
    }

    /** A commit of the project's files right now (into the shadow repository), or null. */
    suspend fun snapshot(context: Context, chatId: String, cwd: File, label: String): String? = withContext(Dispatchers.IO) {
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
        val turns = turns(context, chatId)
        val turn = ChatTurn(turns.size + 1, prompt.take(200), System.currentTimeMillis(), before = before)
        save(context, chatId, turns + turn)
        return turn
    }

    /** Ends a turn: its snapshot after, and how many files changed. */
    suspend fun end(context: Context, chatId: String, cwd: File, turn: ChatTurn) {
        val after = snapshot(context, chatId, cwd, "after: ${turn.prompt.take(60)}")
        val changed = if (turn.before != null && after != null) changedFiles(context, chatId, cwd, turn.before, after).size else null
        val done = turn.copy(endedAt = System.currentTimeMillis(), after = after, filesChanged = changed)
        save(context, chatId, turns(context, chatId).map { if (it.index == turn.index && it.startedAt == turn.startedAt) done else it })
    }

    suspend fun changedFiles(context: Context, chatId: String, cwd: File, from: String, to: String): List<String> =
        git(context, chatId, cwd, "diff", "--name-only", from, to).output.lines().filter { it.isNotBlank() }

    /** The unified diff of what a turn changed. */
    suspend fun diff(context: Context, chatId: String, cwd: File, from: String, to: String): String =
        git(context, chatId, cwd, "diff", "--no-color", "--no-ext-diff", from, to).output.ifBlank { "(no changes)" }

    /**
     * Puts the project's files back as they were at [target]: changed and deleted files are
     * restored, files added since are removed. A snapshot is taken first, so this can be undone.
     */
    suspend fun restore(context: Context, chatId: String, cwd: File, target: String): Result<String> = withContext(Dispatchers.IO) {
        val now = snapshot(context, chatId, cwd, "before restoring ${target.take(7)}")
            ?: return@withContext Result.failure(IllegalStateException("Couldn't take a safety snapshot first"))
        val status = git(context, chatId, cwd, "diff", "--name-status", "--no-renames", target, now).output.lines().filter { it.isNotBlank() }
        val added = status.filter { it.startsWith("A") }.map { it.substringAfter('\t') }
        val restoreList = status.filterNot { it.startsWith("A") }.map { it.substringAfter('\t') }
        for (path in added) File(cwd, path).takeIf { it.canonicalPath.startsWith(cwd.canonicalPath + File.separator) }?.delete()
        if (restoreList.isNotEmpty()) {
            val r = git(context, chatId, cwd, "checkout", target, "--", *restoreList.toTypedArray())
            if (!r.ok) return@withContext Result.failure(IllegalStateException(r.errorOutput.ifBlank { r.output }.lines().firstOrNull { it.isNotBlank() } ?: "git checkout failed"))
        }
        Result.success(now)
    }

    /** Turns, time and the agent's last reported usage, for one chat. */
    data class Stats(val turns: Int, val millis: Long, val filesChanged: Int)

    fun stats(context: Context, chatId: String): Stats = turns(context, chatId).let { t ->
        Stats(t.size, t.sumOf { it.millis }, t.sumOf { it.filesChanged ?: 0 })
    }
}
