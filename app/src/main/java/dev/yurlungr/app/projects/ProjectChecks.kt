package dev.yurlungr.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.projects.Git.Companion.shellQuote
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

enum class Severity { Error, Warning }

/** A compile or lint message at a place in the code. */
data class Problem(val file: String, val line: Int, val col: Int?, val severity: Severity, val message: String)

enum class TestStatus { Passed, Failed, Skipped }

data class TestCase(val suite: String, val name: String, val status: TestStatus, val message: String? = null, val file: String? = null, val line: Int? = null)

/** A command run for a stack, in its folder. */
data class CheckCommand(val dir: File, val command: String)

/**
 * The project's checks: the compile/lint and test commands for its stacks, run in a terminal with
 * their output kept under `.yurlungr/` (git-ignored), and parsers that turn that output into
 * problems and test results.
 */
object ProjectChecks {
    const val DIR = ".yurlungr"
    fun problemsLog(root: File) = File(root, "$DIR/problems.log")
    fun testsLog(root: File) = File(root, "$DIR/tests.log")
    fun resultsDir(root: File) = File(root, "$DIR/test-results")

    /** How much of a check or test log is read: its end, where the errors and the summary are. */
    const val MAX_LOG_BYTES = 2 * 1024 * 1024
    /** Reports (JUnit XML, Jest JSON) bigger than this are skipped: cut short they wouldn't parse anyway. */
    const val MAX_REPORT_BYTES = 8L * 1024 * 1024

    /**
     * The last [max] bytes of [file] as text (all of it when it's smaller), starting at a line:
     * a runaway log (a watch mode left on, a test printing in a loop) mustn't be read whole on every refresh.
     */
    fun readTail(file: File, max: Int = MAX_LOG_BYTES): String {
        if (!file.isFile) return ""
        val length = file.length()
        if (length <= max) return file.readText()
        val bytes = ByteArray(max)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - max)
            raf.readFully(bytes)
        }
        val text = String(bytes, Charsets.UTF_8)
        return text.substring(text.indexOf('\n') + 1)
    }

    private fun deps(dir: File): Set<String> = runCatching {
        val o = Json.parseToJsonElement(File(dir, "package.json").readText()) as JsonObject
        listOf("dependencies", "devDependencies").flatMap { (o[it] as? JsonObject)?.keys.orEmpty() }.toSet()
    }.getOrDefault(emptySet())

    fun checkCommands(stacks: List<DetectedStack>): List<CheckCommand> = stacks.flatMap { s ->
        val gradle = if (File(s.dir, "gradlew").isFile) "./gradlew" else "gradle"
        when (s.id) {
            "node" -> {
                val d = deps(s.dir)
                listOfNotNull(
                    "npx --no-install tsc --noEmit --pretty false".takeIf { "typescript" in d || File(s.dir, "tsconfig.json").isFile },
                    "npx --no-install eslint . -f unix".takeIf { "eslint" in d },
                )
            }
            "android" -> listOf("$gradle --console=plain compileDebugSources")
            "gradle" -> listOf("$gradle --console=plain classes testClasses")
            "rust" -> listOf("cargo check --message-format short")
            "go" -> listOf("go vet ./...")
            "python" -> listOf("if command -v ruff >/dev/null; then ruff check --output-format concise .; else python -m compileall -q -x '/(\\.venv|node_modules)/' .; fi")
            "dotnet" -> listOf("dotnet build -nologo -clp:NoSummary")
            else -> emptyList()
        }.map { CheckCommand(s.dir, it) }
    }

    fun testCommands(root: File, stacks: List<DetectedStack>): List<CheckCommand> {
        val results = resultsDir(root).path
        return stacks.flatMapIndexed { i, s ->
            val gradle = if (File(s.dir, "gradlew").isFile) "./gradlew" else "gradle"
            when (s.id) {
                "node" -> {
                    val d = deps(s.dir)
                    when {
                        "vitest" in d -> listOf("npx --no-install vitest run --reporter=default --reporter=junit --outputFile=${shellQuote("$results/vitest-$i.xml")}")
                        "jest" in d -> listOf("npx --no-install jest --ci --json --outputFile=${shellQuote("$results/jest-$i.json")}")
                        else -> listOf("npm test")
                    }
                }
                "android" -> listOf("$gradle --console=plain testDebugUnitTest")
                "gradle" -> listOf("$gradle --console=plain test")
                "rust" -> listOf("cargo test")
                "go" -> listOf("go test -v ./...")
                "python" -> {
                    val py = if (File(s.dir, ".venv/bin/python").exists()) ".venv/bin/python" else "python"
                    listOf("$py -m pytest --junitxml=${shellQuote("$results/pytest-$i.xml")}")
                }
                "elixir" -> listOf("mix test")
                "dotnet" -> listOf("dotnet test")
                else -> emptyList()
            }.map { CheckCommand(s.dir, it) }
        }
    }

    /** One terminal command: each check in its folder, all output also written to [log]. */
    fun terminalCommand(root: File, commands: List<CheckCommand>, log: File, clearResults: Boolean): String {
        val steps = commands.joinToString("; ") { c -> "(cd ${shellQuote(c.dir.path)} && ${c.command})" }
        val prep = buildString {
            append("cd ${shellQuote(root.path)} && mkdir -p ${shellQuote(resultsDir(root).path)} && ")
            append("printf '*\\n' > ${shellQuote(File(root, "$DIR/.gitignore").path)} && ")
            if (clearResults) append("rm -f ${shellQuote(resultsDir(root).path)}/* && ")
        }
        return "$prep{ $steps; } 2>&1 | tee ${shellQuote(log.path)}"
    }

    // ---- Problems ----

    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    private val KOTLIN = Regex("^([ew]): (?:file://)?(/[^:]+?):(\\d+):(\\d+) (.*)$")
    private val PAREN = Regex("^(.+?)\\((\\d+),(\\d+)\\): (error|warning) [A-Za-z]*\\d*:? ?(.*?)(?: \\[[^\\]]*])?$")
    private val JAVAC = Regex("^(.+\\.java):(\\d+): (error|warning): (.*)$")
    private val GENERIC = Regex("^(.+?):(\\d+):(\\d+):\\s*(?:(error|warning|note)(?:\\[[^\\]]*])?:\\s*)?(.*)$")
    private val PY_FILE = Regex("^\\s*File \"(.+)\", line (\\d+)")
    private val PY_ERROR = Regex("^\\s*(\\w*Error): (.*)$")

    /** Compile and lint output, in the formats the usual tools print, into problems. */
    fun parseProblems(output: String, root: File): List<Problem> {
        val out = LinkedHashSet<Problem>()
        var pyFile: Pair<String, Int>? = null
        fun rel(path: String): String {
            val p = path.trim().removePrefix("./")
            val base = root.invariantSeparatorsPath + "/"
            return if (p.startsWith(base)) p.removePrefix(base) else p
        }
        fun looksLikeFile(p: String) = !p.contains("://") && !p.startsWith(" ") && (p.contains('.') || p.contains('/')) && p.length < 300
        for (raw in output.lineSequence()) {
            val line = ANSI.replace(raw, "").trimEnd()
            KOTLIN.find(line)?.let { m ->
                out += Problem(rel(m.groupValues[2]), m.groupValues[3].toInt(), m.groupValues[4].toIntOrNull(),
                    if (m.groupValues[1] == "e") Severity.Error else Severity.Warning, m.groupValues[5])
                continue
            }
            PAREN.find(line)?.takeIf { looksLikeFile(it.groupValues[1]) }?.let { m ->
                out += Problem(rel(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull(),
                    if (m.groupValues[4] == "warning") Severity.Warning else Severity.Error, m.groupValues[5])
                continue
            }
            JAVAC.find(line)?.let { m ->
                out += Problem(rel(m.groupValues[1]), m.groupValues[2].toInt(), null, if (m.groupValues[3] == "warning") Severity.Warning else Severity.Error, m.groupValues[4])
                continue
            }
            PY_FILE.find(line)?.let { m -> pyFile = m.groupValues[1] to m.groupValues[2].toInt(); continue }
            if (pyFile != null) PY_ERROR.find(line)?.let { m ->
                out += Problem(rel(pyFile!!.first), pyFile!!.second, null, Severity.Error, "${m.groupValues[1]}: ${m.groupValues[2]}")
                pyFile = null
                continue
            }
            GENERIC.find(line)?.takeIf { looksLikeFile(it.groupValues[1]) }?.let { m ->
                val message = m.groupValues[5]
                val severity = when {
                    m.groupValues[4] == "warning" || m.groupValues[4] == "note" || message.endsWith("/Warning]") || message.contains("[Warning/") -> Severity.Warning
                    else -> Severity.Error
                }
                out += Problem(rel(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull(), severity, message)
            }
        }
        return out.toList()
    }

    // ---- Tests ----

    private val LOCATION = Regex("([\\w./-]+\\.(?:kt|java|py|ts|tsx|js|jsx|mjs|go|rs|cs|ex|exs|rb)):(\\d+)")

    private fun locate(text: String?): Pair<String, Int>? = text?.let { LOCATION.find(it) }?.let { it.groupValues[1] to it.groupValues[2].toInt() }

    /** JUnit XML (Gradle, pytest, Vitest…). */
    fun parseJunit(xml: String): List<TestCase> = runCatching {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val cases = doc.getElementsByTagName("testcase")
        (0 until cases.length).map { i ->
            val e = cases.item(i) as Element
            fun child(tag: String) = e.getElementsByTagName(tag).item(0) as? Element
            val failure = child("failure") ?: child("error")
            val message = failure?.let { f -> f.getAttribute("message").ifBlank { f.textContent.lineSequence().firstOrNull { it.isNotBlank() } }?.trim() }
            val where = locate(failure?.textContent)
            TestCase(
                suite = e.getAttribute("classname").ifBlank { (e.parentNode as? Element)?.getAttribute("name").orEmpty() },
                name = e.getAttribute("name"),
                status = when { failure != null -> TestStatus.Failed; child("skipped") != null -> TestStatus.Skipped; else -> TestStatus.Passed },
                message = message,
                file = e.getAttribute("file").ifBlank { null } ?: where?.first,
                line = e.getAttribute("line").toIntOrNull() ?: where?.second,
            )
        }
    }.getOrDefault(emptyList())

    /** Jest's --json report. */
    fun parseJest(json: String): List<TestCase> = runCatching {
        val root = Json.parseToJsonElement(json) as JsonObject
        (root["testResults"] as? JsonArray).orEmpty().flatMap { file ->
            val f = file as JsonObject
            val path = f["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            (f["assertionResults"] as? JsonArray).orEmpty().map { a ->
                val o = a as JsonObject
                val failure = (o["failureMessages"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
                TestCase(
                    suite = path.substringAfterLast('/'),
                    name = o["fullName"]?.jsonPrimitive?.contentOrNull ?: o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    status = when (o["status"]?.jsonPrimitive?.contentOrNull) { "passed" -> TestStatus.Passed; "failed" -> TestStatus.Failed; else -> TestStatus.Skipped },
                    message = failure?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { ANSI.replace(it, "").trim() },
                    file = locate(failure)?.first ?: path,
                    line = locate(failure)?.second,
                )
            }
        }
    }.getOrDefault(emptyList())

    /** `go test -v` and `cargo test` output from the tests log. */
    fun parseTextTests(output: String): List<TestCase> {
        val out = ArrayList<TestCase>()
        val goDetail = HashMap<String, String>()
        var goCurrent: String? = null
        val cargoPanics = HashMap<String, String>()
        var cargoCurrent: String? = null
        val lines = output.lineSequence().map { ANSI.replace(it, "") }.toList()
        for (l in lines) {
            Regex("^=== RUN\\s+(\\S+)").find(l)?.let { goCurrent = it.groupValues[1] }
            if (goCurrent != null && l.startsWith("    ") && LOCATION.containsMatchIn(l)) goDetail.putIfAbsent(goCurrent!!, l.trim())
            Regex("^---- (\\S+) stdout ----").find(l)?.let { cargoCurrent = it.groupValues[1] }
            if (cargoCurrent != null && (l.contains("panicked at") || l.startsWith("assertion"))) cargoPanics.merge(cargoCurrent!!, l.trim()) { a, b -> "$a $b" }
        }
        for (l in lines) {
            Regex("^\\s*--- (PASS|FAIL|SKIP): (\\S+)").find(l)?.let { m ->
                val name = m.groupValues[2]
                val detail = goDetail[name]
                out += TestCase("go", name, when (m.groupValues[1]) { "PASS" -> TestStatus.Passed; "FAIL" -> TestStatus.Failed; else -> TestStatus.Skipped },
                    detail, locate(detail)?.first, locate(detail)?.second)
            }
            Regex("^test (\\S+) \\.\\.\\. (ok|FAILED|ignored)").find(l)?.let { m ->
                val name = m.groupValues[1]
                val panic = cargoPanics[name]
                out += TestCase("cargo", name, when (m.groupValues[2]) { "ok" -> TestStatus.Passed; "FAILED" -> TestStatus.Failed; else -> TestStatus.Skipped },
                    panic, locate(panic)?.first, locate(panic)?.second)
            }
        }
        return out
    }

    /** Every result from the last run: our results folder, Gradle's, and the tests log. */
    fun loadTests(root: File, stacks: List<DetectedStack>): List<TestCase> {
        val since = testsLog(root).takeIf { it.isFile }?.lastModified()?.minus(10 * 60_000) ?: return emptyList()
        val files = resultsDir(root).listFiles().orEmpty().toMutableList()
        for (s in stacks.filter { it.id == "android" || it.id == "gradle" }) {
            File(s.dir, ".").walkTopDown().onEnter { it.name !in setOf("node_modules", ".git", ".gradle", "src", "intermediates") }.maxDepth(5)
                .filter { it.isFile && it.extension == "xml" && it.parentFile?.parentFile?.name == "test-results" && it.lastModified() >= since }
                .forEach { files += it }
        }
        return files.filter { it.length() <= MAX_REPORT_BYTES }.flatMap { f ->
            when (f.extension) {
                "xml" -> parseJunit(f.readText())
                "json" -> parseJest(f.readText())
                else -> emptyList()
            }
        } + parseTextTests(readTail(testsLog(root)))
    }

    /** A path from a report, found under [root] (reports often give only a file name). */
    fun resolve(root: File, path: String): File? {
        File(path).takeIf { it.isAbsolute && it.isFile }?.let { return it }
        File(root, path).takeIf { it.isFile }?.let { return it }
        val name = path.substringAfterLast('/')
        return root.walkTopDown().onEnter { it.name !in setOf("node_modules", ".git", "build", ".gradle", "target", ".venv", "dist") }
            .maxDepth(12).firstOrNull { it.isFile && it.name == name && it.path.endsWith(path.removePrefix("./")) }
            ?: root.walkTopDown().onEnter { it.name !in setOf("node_modules", ".git", "build", ".gradle", "target", ".venv", "dist") }
                .maxDepth(12).firstOrNull { it.isFile && it.name == name }
    }
}

/** The Checks tab: run checks and tests, then see problems and failures; tap to open, or ask an agent. */
@Composable
internal fun ChecksTab(
    root: File,
    stacks: List<DetectedStack>,
    refreshKey: Int,
    runInTerminal: (String, String) -> Unit,
    onOpenAt: (File, Int?) -> Unit,
    onAskAgent: (String) -> Unit,
) {
    var problems by remember(root.path) { mutableStateOf<List<Problem>?>(null) }
    var tests by remember(root.path) { mutableStateOf<List<TestCase>?>(null) }
    var checkedAt by remember(root.path) { mutableStateOf<Long?>(null) }
    var testedAt by remember(root.path) { mutableStateOf<Long?>(null) }
    val checks = remember(stacks) { ProjectChecks.checkCommands(stacks) }
    // Reads package.json and friends, so off the main thread.
    val testRuns by produceState(emptyList<CheckCommand>(), root, stacks) {
        value = withContext(Dispatchers.IO) { runCatching { ProjectChecks.testCommands(root, stacks) }.getOrDefault(value) }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(root, stacks, refreshKey) {
        withContext(Dispatchers.IO) {
            val log = ProjectChecks.problemsLog(root)
            // The log may be mid-rewrite by a running check: unreadable just means no results yet.
            problems = if (log.isFile) runCatching { ProjectChecks.parseProblems(ProjectChecks.readTail(log), root) }.getOrNull() else null
            checkedAt = log.takeIf { it.isFile }?.lastModified()
            tests = runCatching { ProjectChecks.loadTests(root, stacks) }.getOrNull()?.takeIf { ProjectChecks.testsLog(root).isFile }
            testedAt = ProjectChecks.testsLog(root).takeIf { it.isFile }?.lastModified()
        }
    }
    val colors = MaterialTheme.colorScheme
    val tones = AppColors.status
    // A bare file name means walking the tree: never on the main thread.
    fun open(path: String, line: Int?) = scope.launch { withContext(Dispatchers.IO) { ProjectChecks.resolve(root, path) }?.let { onOpenAt(it, line) } }
    fun ago(t: Long?) = t?.let { android.text.format.DateUtils.getRelativeTimeSpanString(it).toString() }

    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainer).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (checks.isEmpty() && testRuns.isEmpty()) "No checks or tests found for this project's stacks."
                    else "Runs in a terminal; results show here when it's done.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = checks.isNotEmpty(), modifier = Modifier.weight(1f), onClick = {
                        runInTerminal(ProjectChecks.terminalCommand(root, checks, ProjectChecks.problemsLog(root), clearResults = false), "Check · ${root.name}")
                    }) { PhIcon(Ph.ShieldCheck, null, size = 16.dp); Spacer(Modifier.width(6.dp)); Text("Check") }
                    FilledTonalButton(enabled = testRuns.isNotEmpty(), modifier = Modifier.weight(1f), onClick = {
                        runInTerminal(ProjectChecks.terminalCommand(root, testRuns, ProjectChecks.testsLog(root), clearResults = true), "Tests · ${root.name}")
                    }) { PhIcon(Ph.TestTube, null, size = 16.dp); Spacer(Modifier.width(6.dp)); Text("Run tests") }
                }
            }
        }

        // Phone-specific failures (and runs that failed with nothing parsed): fixes and Ask the agent.
        item {
            ChecksFailureHelp(root, stacks, refreshKey, checks.joinToString("; ") { it.command }, testRuns.joinToString("; ") { it.command },
                problemsParsed = !problems.isNullOrEmpty(), testsParsed = !tests.isNullOrEmpty())
        }

        // Problems
        val list = problems
        item {
            val errors = list.orEmpty().count { it.severity == Severity.Error }
            val warnings = list.orEmpty().size - errors
            SectionTitle("Problems", when {
                list == null -> "Not checked yet"
                list.isEmpty() -> "None · ${ago(checkedAt)}"
                else -> "$errors error${if (errors == 1) "" else "s"}, $warnings warning${if (warnings == 1) "" else "s"} · ${ago(checkedAt)}"
            }, action = if (list.orEmpty().any { it.severity == Severity.Error }) ("Fix with agent" to {
                onAskAgent("Fix these problems reported by the project's checks (`${checks.joinToString("; ") { it.command }}`):\n\n" +
                    list.orEmpty().filter { it.severity == Severity.Error }.take(40).joinToString("\n") { "- ${it.file}:${it.line}: ${it.message}" } +
                    "\n\nRun the checks again afterwards to confirm.")
            }) else null)
        }
        if (list != null) for ((file, inFile) in list.groupBy { it.file }) {
            item(key = "p:$file") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainer).padding(vertical = 4.dp)) {
                    Text(file, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    for (p in inFile.sortedBy { it.line }.take(50)) {
                        Row(Modifier.fillMaxWidth().clickable { open(p.file, p.line) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            PhIcon(if (p.severity == Severity.Error) Ph.XCircle else Ph.Warning, p.severity.name, size = 15.dp,
                                tint = if (p.severity == Severity.Error) colors.error else tones.warning, modifier = Modifier.padding(top = 1.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("${p.line}" + (p.col?.let { ":$it" } ?: ""), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                color = colors.onSurfaceVariant, modifier = Modifier.width(52.dp))
                            Text(p.message, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Tests
        val results = tests
        item {
            val failed = results.orEmpty().count { it.status == TestStatus.Failed }
            val passed = results.orEmpty().count { it.status == TestStatus.Passed }
            val skipped = results.orEmpty().count { it.status == TestStatus.Skipped }
            SectionTitle("Tests", when {
                results == null -> "Not run yet"
                results.isEmpty() -> "Ran ${ago(testedAt)}; no results this app can read (see the terminal)"
                else -> "$passed passed, $failed failed" + (if (skipped > 0) ", $skipped skipped" else "") + " · ${ago(testedAt)}"
            }, action = if (failed > 0) ("Fix with agent" to {
                onAskAgent("These tests fail (`${testRuns.joinToString("; ") { it.command }}`):\n\n" +
                    results.orEmpty().filter { it.status == TestStatus.Failed }.take(30).joinToString("\n") { "- ${it.suite} › ${it.name}: ${it.message.orEmpty().take(300)}" } +
                    "\n\nFind the cause and fix it (the code, or the test if it's wrong). Run the tests again to confirm.")
            }) else null)
        }
        items(results.orEmpty().filter { it.status == TestStatus.Failed }.take(100)) { t ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.errorContainer.copy(alpha = 0.35f))
                    .clickable(enabled = t.file != null) { open(t.file!!, t.line) }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(t.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t.suite + (t.file?.let { " · $it" + (t.line?.let { l -> ":$l" } ?: "") } ?: ""), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                t.message?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String, action: Pair<String, () -> Unit>?) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.let { (label, onClick) ->
            TextButton(onClick = onClick) {
                PhIcon(Ph.Sparkle, null, size = 14.dp)
                Spacer(Modifier.width(4.dp))
                Text(label)
            }
        }
    }
}
