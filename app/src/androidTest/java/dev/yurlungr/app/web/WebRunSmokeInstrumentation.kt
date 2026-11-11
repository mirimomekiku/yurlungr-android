package dev.yurlungr.app.web

import android.app.Instrumentation
import android.os.Bundle
import dev.yurlungr.app.terminal.TerminalSessions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Explicit device-only smoke runner. No screen interaction and no changes to user projects. */
class WebRunSmokeInstrumentation : Instrumentation() {
    private var nativeOnly = false
    private var scriptsOnly = false
    private var features = false
    private var selfTest: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        nativeOnly = arguments?.getString("nativeOnly") == "true"
        scriptsOnly = arguments?.getString("scriptsOnly") == "true"
        features = arguments?.getString("features") == "true"
        selfTest = arguments?.getString("selfTest")
        start()
    }

    override fun onStart() {
        val output = Bundle()
        val fixture = File(targetContext.cacheDir,"web-instrumentation-${UUID.randomUUID()}")
        val roots = mutableListOf<File>()
        var resultCode = RESULT_OK
        try {
            selfTest?.let { mode ->
                // The in-runtime self-test, with the environment agents get ("full" installs two small packs).
                val paths = dev.yurlungr.app.runtime.RuntimePaths(targetContext)
                dev.yurlungr.app.runtime.RuntimeAssets.install(targetContext, paths)
                val script = File(paths.libexec, "self-test.sh").path
                val r = kotlinx.coroutines.runBlocking {
                    dev.yurlungr.app.runtime.CommandRunner.run(paths, listOfNotNull(paths.bash.path, script, "--full".takeIf { mode == "full" }), timeoutMs = 30 * 60_000L)
                }
                output.putString("stream", r.output.replace(Regex("\u001B\\[[0-9;]*m"), "") + "\nexit ${r.exitCode}\n")
                output.putString("result", if (r.ok) "PASS" else "FAIL")
                finish(if (r.ok) RESULT_OK else RESULT_CANCELED, output)
                return
            }
            if (features) {
                // The 0.0.4 features against the real runtime (no screen, temporary folders only).
                val lines = dev.yurlungr.app.runtime.FeatureSmokeChecks.run(this)
                output.putString("stream", lines.joinToString("\n") + "\n")
                output.putString("result", "PASS")
                finish(RESULT_OK, output)
                return
            }
            if (scriptsOnly) {
                val skipped = dev.yurlungr.app.runtime.RuntimeScriptSmokeChecks.run(this)
                output.putString("stream", "PASS: installed script encoding, syntax, failures, logs, retries, setup lock and status\n" +
                    (if (skipped.isEmpty()) "" else "$skipped\n"))
                output.putString("result", "PASS")
                finish(RESULT_OK, output)
                return
            }
            com.termux.terminal.NativeSmokeChecks.run(targetContext)
            if (nativeOnly) {
                output.putString("stream", "PASS: native PTY create, resize, subprocess execution, wait and close\n")
                output.putString("result", "PASS")
                finish(RESULT_OK, output)
                return
            }
            fixture.mkdirs()
            File(fixture,"index.html").writeText("<h1>Yurlungr instrumentation preview</h1>")
            roots += fixture
            val config = WebRunConfig(WebProject(fixture,fixture,WebKind.Static,"Static site"),WebPackageManager.Npm,"",freePort(),false,openBrowser=false)
            val first = main {
                val run = WebRunSessions.start(targetContext,fixture,config)
                check(run.busy) { "Run wasn't reserved while preparing" }
                check(WebRunSessions.start(targetContext,fixture,config) === run) { "Starting allowed a duplicate" }
                run
            }
            waitFor("static server running") { first.state == WebRunState.Running }
            check(fetch(first.url).contains("instrumentation preview"))
            check(fetch("${first.url}/.yurlungr/health/${first.id}").contains(first.id)) // exercises Android cleartext policy
            val duplicate = main { WebRunSessions.start(targetContext,fixture,config) }
            check(first === duplicate && first.busy) { "Running allowed a duplicate" }
            val firstTerminal = main { checkNotNull(first.terminal) }
            main {
                TerminalSessions.endProcess(firstTerminal)
                check(first.busy && WebRunSessions.start(targetContext,fixture,config) === first) { "Stopping allowed a duplicate" }
            }
            waitFor("managed End") { first.state == WebRunState.Stopped && !firstTerminal.isRunning && !first.busy }
            check(main { firstTerminal in TerminalSessions.sessions }) { "End removed the log terminal" }
            main { TerminalSessions.restart(targetContext,firstTerminal) }
            waitFor("managed Restart") { WebRunSessions.get(fixture)?.let { it.id != first.id && it.state == WebRunState.Running } == true }
            val second = main { checkNotNull(WebRunSessions.get(fixture)) }
            check(fetch(second.url).contains("instrumentation preview"))
            val secondTerminal = main { checkNotNull(second.terminal) }
            main { TerminalSessions.close(targetContext,secondTerminal) }
            waitFor("managed Close") { second.state == WebRunState.Stopped && secondTerminal !in TerminalSessions.sessions }
            check(runCatching { fetch(second.url) }.isFailure) { "Closed server still accepts requests" }

            val frameworkResults = mutableListOf<String>()
            for ((fixtureName,marker,asset) in listOf(
                Triple("vite","Yurlungr Vite device check","/main.js"),
                Triple("next","Yurlungr Next device check","/api/check"),
                Triple("astro","Yurlungr Astro device check",null))) {
                val site = File(targetContext.cacheDir,"web-$fixtureName-smoke/site")
                val packageName = if (fixtureName == "next") "next" else fixtureName
                if (!File(site,"node_modules/$packageName/package.json").isFile) {
                    frameworkResults += "$fixtureName: not present (optional fixture)"
                    continue
                }
                roots += site
                val project = ProjectDetector.detect(site).web.single()
                val run = main { WebRunSessions.start(targetContext,site,WebRunConfig(project,WebPackageManager.Npm,"dev",freePort(),false,
                    openBrowser=false,mode=WebLaunchMode.Framework,startupTimeoutSeconds=300)) }
                waitFor("$fixtureName running",320000) {
                    check(run.state !in setOf(WebRunState.Failed,WebRunState.NeedsSetup,WebRunState.CompatibilityFailure)) { "$fixtureName: ${run.message}" }
                    run.state == WebRunState.Running
                }
                check(fetch(run.url).contains(marker))
                asset?.let { check(fetch("${run.url}$it").isNotBlank()) }
                val terminal = main { checkNotNull(run.terminal) }
                main { TerminalSessions.close(targetContext,terminal) }
                waitFor("$fixtureName Close") { run.state == WebRunState.Stopped && terminal !in TerminalSessions.sessions }
                check(runCatching { fetch(run.url) }.isFailure) { "$fixtureName server still accepts requests" }
                frameworkResults += "$fixtureName: passed"
            }
            val regression = File(targetContext.cacheDir,"web-project-regression/site")
            if (File(regression,"node_modules/next/package.json").isFile) {
                roots += regression
                val project = ProjectDetector.detect(regression).web.single()
                val run = main { WebRunSessions.start(targetContext,regression,WebRunConfig(project,WebPackageManager.Npm,"dev",freePort(),false,
                    openBrowser=false,mode=WebLaunchMode.Framework,startupTimeoutSeconds=300)) }
                waitFor("Next + Tailwind compiling",320000) {
                    check(run.busy)
                    check(WebRunSessions.start(targetContext,regression,run.config) === run) { "Compilation allowed a duplicate" }
                    check(run.state !in setOf(WebRunState.Failed,WebRunState.NeedsSetup,WebRunState.CompatibilityFailure)) { run.message }
                    run.state == WebRunState.Running
                }
                val html = fetch(run.url)
                val css = Regex("""href=["]([^"]+[.]css(?:[?][^"]*)?)["]""").findAll(html).map { it.groupValues[1] }.toList()
                check(css.isNotEmpty()) { "No CSS stylesheet generated" }
                check(css.joinToString("\n") { fetch(run.url + it.replace("&amp;","&")) }.contains("tailwindcss")) { "Tailwind CSS compilation failed" }
                main { run.closeAfterStop = true; WebRunSessions.stop(targetContext,run) }
                waitFor("Next + Tailwind Close") { !run.busy }
                frameworkResults += "Next 15 + Tailwind/DaisyUI: passed"
            }
            output.putString("stream","PASS: static preview, loopback health, duplicate prevention, managed End/Restart/Close; ${frameworkResults.joinToString("; ")}\n")
            output.putString("result","PASS")
        } catch (error: Throwable) {
            output.putString("stream","FAIL: ${error.stackTraceToString()}\n")
            output.putString("result","FAIL")
            resultCode = RESULT_CANCELED
        } finally {
            roots.forEach { root ->
                main { WebRunSessions.get(root)?.let { it.closeAfterStop = true; WebRunSessions.stop(targetContext,it) } }
                targetContext.getSharedPreferences("web_runs",0).edit().remove(root.canonicalPath).commit()
            }
            fixture.deleteRecursively()
        }
        finish(resultCode,output)
    }

    private fun freePort(): Int = java.net.ServerSocket(0,0,java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=2000; connection.readTimeout=2000
            check(connection.responseCode==200)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun waitFor(label: String, timeout: Long = 20000, condition: () -> Boolean) {
        val until=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) {
            if(main(condition)) return
            Thread.sleep(100)
        }
        error("Timed out: $label")
    }

    companion object { private const val RESULT_OK=-1; private const val RESULT_CANCELED=0 }
}
