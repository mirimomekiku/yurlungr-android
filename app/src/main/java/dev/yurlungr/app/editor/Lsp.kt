package dev.yurlungr.app.editor

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import dev.yurlungr.app.runtime.RuntimeEnv
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A language server: how to start it, and the language id it expects. */
data class LspServer(val name: String, val command: List<String>, val languageId: String)

/** A problem the server reports, 0-based line and column as LSP sends them. */
data class LspDiagnostic(val line: Int, val col: Int, val severity: Int, val message: String, val source: String?) {
    val isError: Boolean get() = severity == 1
}

data class LspLocation(val file: File, val line: Int)

object LspServers {
    /** Candidates per file type, first installed wins (the Language servers pack, or npm/pip). */
    private val BY_EXT: Map<String, List<LspServer>> = buildMap {
        val ts = listOf(LspServer("typescript-language-server", listOf("typescript-language-server", "--stdio"), "typescript"))
        for (e in listOf("ts", "tsx", "js", "jsx", "mjs", "cjs")) put(e, ts.map { it.copy(languageId = if (e.startsWith("t")) (if (e == "tsx") "typescriptreact" else "typescript") else if (e == "jsx") "javascriptreact" else "javascript") })
        put("rs", listOf(LspServer("rust-analyzer", listOf("rust-analyzer"), "rust")))
        put("go", listOf(LspServer("gopls", listOf("gopls"), "go")))
        put("lua", listOf(LspServer("lua-language-server", listOf("lua-language-server"), "lua")))
        for (e in listOf("c", "h", "cc", "cpp", "hpp", "cxx")) put(e, listOf(LspServer("clangd", listOf("clangd"), if (e == "c" || e == "h") "c" else "cpp")))
        put("py", listOf(
            LspServer("pyright", listOf("pyright-langserver", "--stdio"), "python"),
            LspServer("pylsp", listOf("pylsp"), "python"),
            LspServer("ruff", listOf("ruff", "server"), "python"),
        ))
        put("sh", listOf(LspServer("bash-language-server", listOf("bash-language-server", "start"), "shellscript")))
    }

    fun forFile(paths: RuntimePaths, file: File): LspServer? {
        val dirs = RuntimeEnv.map(paths)["PATH"].orEmpty().split(':').map(::File)
        return BY_EXT[file.extension.lowercase()]?.firstOrNull { s -> dirs.any { File(it, s.command.first()).canExecute() } }
    }

    /** Names of servers a file type could use, for a hint when none is installed. */
    fun candidates(file: File): List<String> = BY_EXT[file.extension.lowercase()].orEmpty().map { it.name }
}

/**
 * A language server over its stdio (JSON-RPC with Content-Length headers): diagnostics as they're
 * published, and definition, hover and completion on request. One per project and server.
 */
class LspClient(private val paths: RuntimePaths, val root: File, val server: LspServer) {
    private val process: Process
    private val out: OutputStream
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    /** uri → diagnostics, observable by the editor. */
    val diagnostics = mutableStateMapOf<String, List<LspDiagnostic>>()
    private val versions = HashMap<String, Int>()
    @Volatile var alive = true
        private set

    init {
        val script = "${RuntimeEnv.PROFILE_SNIPPET}; exec " + server.command.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        process = ProcessBuilder(paths.bash.path, "-c", script).directory(root)
            .apply { environment().run { clear(); putAll(RuntimeEnv.map(paths, root)) } }
            .start()
        out = process.outputStream
        // Servers log to stderr; read it so a full pipe can't block them.
        Thread({ runCatching { val buf = ByteArray(4096); val err = process.errorStream; while (err.read(buf) >= 0) Unit } }, "lsp-err").apply { isDaemon = true }.start()
        Thread({ readLoop() }, "lsp-${server.name}").apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        val input = BufferedInputStream(process.inputStream)
        try {
            while (true) {
                var length = -1
                while (true) {
                    val header = readLine(input) ?: return
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                }
                if (length < 0) continue
                val body = ByteArray(length)
                var read = 0
                while (read < length) { val n = input.read(body, read, length - read); if (n < 0) return; read += n }
                handle(Json.parseToJsonElement(String(body, Charsets.UTF_8)) as? JsonObject ?: continue)
            }
        } catch (e: Exception) {
            Log.w("Lsp", "${server.name} stopped: ${e.message}")
        } finally {
            alive = false
            pending.values.forEach { it.complete(null) }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun handle(msg: JsonObject) {
        val id = (msg["id"] as? JsonPrimitive)?.intOrNull
        val method = (msg["method"] as? JsonPrimitive)?.contentOrNull
        when {
            method == "textDocument/publishDiagnostics" -> {
                val p = msg["params"] as? JsonObject ?: return
                val uri = p["uri"]?.jsonPrimitive?.contentOrNull ?: return
                diagnostics[uri] = (p["diagnostics"] as? JsonArray).orEmpty().mapNotNull { d ->
                    val o = d as? JsonObject ?: return@mapNotNull null
                    val start = (o["range"] as? JsonObject)?.get("start") as? JsonObject
                    LspDiagnostic(start?.get("line")?.jsonPrimitive?.intOrNull ?: 0, start?.get("character")?.jsonPrimitive?.intOrNull ?: 0,
                        o["severity"]?.jsonPrimitive?.intOrNull ?: 1, o["message"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["source"]?.jsonPrimitive?.contentOrNull)
                }
            }
            // A request from the server (configuration, progress…): answer with nothing.
            method != null && id != null -> send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", JsonNull) })
            id != null -> pending.remove(id)?.complete(msg["result"])
        }
    }

    @Synchronized
    private fun send(msg: JsonObject) {
        if (!alive) return
        val bytes = msg.toString().toByteArray(Charsets.UTF_8)
        runCatching { out.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray()); out.write(bytes); out.flush() }.onFailure { alive = false }
    }

    private fun notify(method: String, params: JsonObject) = send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) })

    suspend fun request(method: String, params: JsonObject, timeoutMs: Long = 10_000): JsonElement? {
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<JsonElement?>()
        pending[id] = d
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
        return withTimeoutOrNull(timeoutMs) { d.await() }.also { pending.remove(id) }
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        val rootUri = uri(root)
        val result = request("initialize", buildJsonObject {
            put("processId", JsonNull)
            put("rootUri", rootUri)
            put("workspaceFolders", buildJsonArray { add(buildJsonObject { put("uri", rootUri); put("name", root.name) }) })
            putJsonObject("capabilities") {
                putJsonObject("textDocument") {
                    putJsonObject("hover") { put("contentFormat", buildJsonArray { add(JsonPrimitive("plaintext")); add(JsonPrimitive("markdown")) }) }
                    putJsonObject("completion") { putJsonObject("completionItem") { put("snippetSupport", false) } }
                    putJsonObject("definition") {}
                    putJsonObject("publishDiagnostics") {}
                    putJsonObject("synchronization") { put("didSave", false) }
                }
            }
        }, timeoutMs = 60_000)
        if (result != null) notify("initialized", JsonObject(emptyMap()))
        result != null
    }

    fun open(file: File, text: String) {
        versions[uri(file)] = 1
        notify("textDocument/didOpen", buildJsonObject {
            putJsonObject("textDocument") { put("uri", uri(file)); put("languageId", server.languageId); put("version", 1); put("text", text) }
        })
    }

    fun change(file: File, text: String) {
        val v = (versions[uri(file)] ?: 1) + 1
        versions[uri(file)] = v
        notify("textDocument/didChange", buildJsonObject {
            putJsonObject("textDocument") { put("uri", uri(file)); put("version", v) }
            put("contentChanges", buildJsonArray { add(buildJsonObject { put("text", text) }) })
        })
    }

    fun close(file: File) = notify("textDocument/didClose", buildJsonObject { putJsonObject("textDocument") { put("uri", uri(file)) } })

    private fun position(file: File, line: Int, col: Int) = buildJsonObject {
        putJsonObject("textDocument") { put("uri", uri(file)) }
        putJsonObject("position") { put("line", line); put("character", col) }
    }

    suspend fun definition(file: File, line: Int, col: Int): List<LspLocation> {
        val r = request("textDocument/definition", position(file, line, col)) ?: return emptyList()
        val list = when (r) { is JsonArray -> r.toList(); is JsonObject -> listOf(r); else -> emptyList() }
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val target = (o["uri"] ?: o["targetUri"])?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val range = (o["range"] ?: o["targetSelectionRange"]) as? JsonObject
            val l = ((range?.get("start") as? JsonObject)?.get("line"))?.jsonPrimitive?.intOrNull ?: 0
            LspLocation(File(java.net.URI(target)), l + 1)
        }
    }

    suspend fun hover(file: File, line: Int, col: Int): String? {
        val r = request("textDocument/hover", position(file, line, col)) as? JsonObject ?: return null
        fun text(e: JsonElement?): String = when (e) {
            is JsonPrimitive -> e.contentOrNull.orEmpty()
            is JsonObject -> e["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
            is JsonArray -> e.joinToString("\n\n") { text(it) }
            else -> ""
        }
        return text(r["contents"]).trim().ifEmpty { null }
    }

    /** Completion labels and what to insert (at most 60). */
    suspend fun completion(file: File, line: Int, col: Int): List<Pair<String, String>> {
        val r = request("textDocument/completion", position(file, line, col)) ?: return emptyList()
        val items = when (r) { is JsonArray -> r; is JsonObject -> r["items"] as? JsonArray ?: JsonArray(emptyList()); else -> JsonArray(emptyList()) }
        return items.take(60).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val label = o["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val insert = ((o["textEdit"] as? JsonObject)?.get("newText") ?: o["insertText"])?.jsonPrimitive?.contentOrNull ?: label
            label to insert
        }
    }

    fun stop() {
        if (!alive) { process.destroy(); return }
        Thread {
            runCatching { kotlinx.coroutines.runBlocking { request("shutdown", JsonObject(emptyMap()), 3_000) } }
            notify("exit", JsonObject(emptyMap()))
            Thread.sleep(500)
            process.destroy()
        }.start()
    }

    companion object {
        fun uri(f: File): String = f.toURI().toString().replace("file:/", "file:///").replace("file:////", "file:///")
    }
}

/** One server per project and language, shared by the editors that use it; stopped when the last closes. */
object LspManager {
    private val clients = HashMap<String, Pair<LspClient, Int>>()

    suspend fun acquire(context: Context, file: File): LspClient? = withContext(Dispatchers.IO) {
        val paths = RuntimePaths(context)
        val server = LspServers.forFile(paths, file) ?: return@withContext null
        val root = EditorTools.rootOf(file)
        val key = root.path + "|" + server.name
        synchronized(clients) {
            clients[key]?.takeIf { it.first.alive }?.let { (c, n) -> clients[key] = c to n + 1; return@withContext c }
        }
        val client = runCatching { LspClient(paths, root, server) }.getOrNull() ?: return@withContext null
        if (!client.initialize()) { client.stop(); return@withContext null }
        synchronized(clients) { clients[key] = client to 1 }
        client
    }

    fun release(client: LspClient) = synchronized(clients) {
        val key = client.root.path + "|" + client.server.name
        val (c, n) = clients[key] ?: return@synchronized
        if (n <= 1) { clients.remove(key); c.stop() } else clients[key] = c to n - 1
    }
}
