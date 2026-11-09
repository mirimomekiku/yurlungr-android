package dev.yurlungr.app.runtime

import android.app.Instrumentation
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

internal object RuntimeScriptSmokeChecks {
    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val paths = RuntimePaths(context)
        check(paths.isBootstrapInstalled) { "Install the Linux runtime before the script smoke check" }
        RuntimeAssets.install(context, paths)
        val scripts = context.assets.list("").orEmpty().filter { it.endsWith(".sh") }
        for (name in scripts) {
            val file = File(if (name == "spike-test.sh") paths.home else paths.libexec, name)
            val text = file.readText()
            check('\r' !in text && !text.startsWith('\uFEFF')) { "Unnormalized script: $name" }
            check(file.canExecute()) { "Non-executable script: $name" }
            val result = runBlocking { CommandRunner.run(paths, listOf(paths.bash.path, "-n", file.path), timeoutMs = 10_000) }
            check(result.ok) { "$name syntax: ${result.output}" }
        }
        val fixture = File(context.cacheDir, "setup-smoke-${UUID.randomUUID()}")
        try {
            check(fixture.mkdirs())
            val script = File(fixture, "smoke.sh")
            val source = instrumentation.context.assets.open("setup-smoke.sh").bufferedReader().use { it.readText() }
            script.writeText(normalizeRuntimeScript(source))
            val result = runBlocking {
                CommandRunner.run(paths, listOf(paths.bash.path, script.path,
                    File(paths.libexec, "setup-common.sh").path, fixture.path), timeoutMs = 30_000)
            }
            check(result.ok) { "Setup smoke (exit ${result.exitCode}): ${result.output}" }
        } finally {
            fixture.deleteRecursively()
        }
    }
}
