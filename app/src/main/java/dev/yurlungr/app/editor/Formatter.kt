package dev.yurlungr.app.editor

import dev.yurlungr.app.projects.Git.Companion.shellQuote
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Formats with Prettier inside the runtime (`npx prettier`, pinned). Runs in the file's folder
 * with `--stdin-filepath`, so the project's own .prettierrc / .editorconfig apply. The first
 * run downloads Prettier into npm's cache (~8 MB).
 */
object Formatter {
    private const val PRETTIER = "prettier@3.3.3"

    sealed interface Result {
        data class Formatted(val text: String) : Result
        data class Failed(val message: String) : Result
    }

    fun supports(lang: Language) = lang.prettier

    /** Never throws: any failure (no Node, unwritable cache, broken shell) is a [Result.Failed]. */
    suspend fun prettier(paths: RuntimePaths, file: File, text: String): Result = try {
        runPrettier(paths, file, text)
    } catch (e: java.io.IOException) {
        Result.Failed("Couldn't run Prettier: ${e.message ?: e}")
    }

    private suspend fun runPrettier(paths: RuntimePaths, file: File, text: String): Result = withContext(Dispatchers.IO) {
        if (!File(paths.bin, "npx").exists()) {
            return@withContext Result.Failed("Node.js isn't installed. Install the agents (they bring Node), or run: pkg install nodejs-lts")
        }
        val tmp = File(paths.home, ".cache/yurlungr-format").apply { mkdirs() }
        // Per call: two overlapping formats (e.g. one still downloading Prettier) must not swap files.
        val input = File.createTempFile("in-", ".txt", tmp)
        val output = File(tmp, input.name.replace("in-", "out-"))
        input.writeText(text)
        output.delete()
        val script = "npx --yes $PRETTIER --stdin-filepath ${shellQuote(file.path)} " +
            "< ${shellQuote(input.path)} > ${shellQuote(output.path)}"
        val r = CommandRunner.run(
            paths,
            listOf(paths.bash.path, "-c", script),
            cwd = file.parentFile ?: paths.home,
            timeoutMs = 180_000,
            extraEnv = mapOf("NO_UPDATE_NOTIFIER" to "1", "npm_config_update_notifier" to "false"),
        )
        try {
            when {
                r.timedOut -> Result.Failed("Prettier took too long (is the network up for the first download?)")
                r.toolMissing -> Result.Failed(r.describe("Prettier") + "\nCheck Node.js in System checks.")
                !r.ok -> Result.Failed(r.output.trim().lines().filter { it.isNotBlank() }.take(6).joinToString("\n").ifBlank { "Prettier failed (exit ${r.exitCode})" })
                !output.isFile -> Result.Failed("Prettier produced no output")
                else -> Result.Formatted(output.readText())
            }
        } finally {
            input.delete()
            output.delete()
        }
    }
}
