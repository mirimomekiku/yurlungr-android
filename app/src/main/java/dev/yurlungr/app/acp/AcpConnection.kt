package dev.yurlungr.app.acp

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** JSON-RPC error returned by the agent (or synthesized when the agent process dies). */
class AcpException(val code: Int, message: String, val data: JsonElement? = null) : Exception(message) {
    companion object {
        const val AUTH_REQUIRED = -32000
        const val METHOD_NOT_FOUND = -32601
        const val INTERNAL = -32603
        const val PROCESS_EXITED = -1
    }
}

/**
 * Agent Client Protocol connection: newline-delimited JSON-RPC 2.0 over an agent process's
 * stdin/stdout. Every incoming message is handled on [scope] in arrival order, so a prompt's
 * final response is never processed before the `session/update` notifications preceding it.
 */
class AcpConnection(
    private val process: Process,
    private val scope: CoroutineScope,
    private val handler: Handler,
) {
    interface Handler {
        /** Agent → client request (permission, fs). Return the result or throw [AcpException]. */
        suspend fun onRequest(method: String, params: JsonObject): JsonElement
        fun onNotification(method: String, params: JsonObject)
        fun onStderr(line: String) {}
        fun onExit(code: Int) {}
    }

    private sealed interface Incoming {
        data class Message(val json: JsonObject) : Incoming
        data class Exited(val code: Int) : Incoming
    }

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val writer: BufferedWriter = process.outputStream.bufferedWriter()
    private val incoming = Channel<Incoming>(Channel.UNLIMITED)

    @Volatile var exited = false
        private set

    init {
        thread(isDaemon = true, name = "acp-stdout") {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    if (obj != null) incoming.trySend(Incoming.Message(obj)) else Log.d(TAG, "non-JSON stdout: $line")
                }
            }
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            incoming.trySend(Incoming.Exited(code))
        }
        thread(isDaemon = true, name = "acp-stderr") {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    scope.launch { handler.onStderr(line) }
                }
            }
        }
        scope.launch {
            for (msg in incoming) {
                when (msg) {
                    is Incoming.Message -> dispatch(msg.json)
                    is Incoming.Exited -> {
                        exited = true
                        val error = AcpException(AcpException.PROCESS_EXITED, "Agent process exited (code ${msg.code})")
                        pending.values.forEach { it.completeExceptionally(error) }
                        pending.clear()
                        handler.onExit(msg.code)
                        break
                    }
                }
            }
        }
    }

    private fun dispatch(msg: JsonObject) {
        val method = msg["method"]?.jsonPrimitive?.contentOrNull
        val id = msg["id"]
        val params = (msg["params"] as? JsonObject) ?: JsonObject(emptyMap())
        when {
            method == null && id != null -> {
                val deferred = id.jsonPrimitive.longOrNull?.let { pending.remove(it) } ?: return
                val error = msg["error"] as? JsonObject
                if (error != null) {
                    deferred.completeExceptionally(
                        AcpException(
                            error["code"]?.jsonPrimitive?.intOrNull ?: AcpException.INTERNAL,
                            error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error",
                            error["data"],
                        ),
                    )
                } else {
                    deferred.complete(msg["result"] ?: JsonNull)
                }
            }
            method != null && id != null -> scope.launch {
                val response = try {
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("result", handler.onRequest(method, params))
                    }
                } catch (e: Exception) {
                    val code = (e as? AcpException)?.code ?: AcpException.INTERNAL
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("error", buildJsonObject {
                            put("code", code)
                            put("message", e.message ?: e.toString())
                        })
                    }
                }
                send(response)
            }
            method != null -> handler.onNotification(method, params)
        }
    }

    suspend fun request(method: String, params: JsonObject): JsonElement {
        if (exited) throw AcpException(AcpException.PROCESS_EXITED, "Agent process is not running")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        try {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            })
            return deferred.await()
        } finally {
            pending.remove(id)
        }
    }

    suspend fun notify(method: String, params: JsonObject) {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        })
    }

    private suspend fun send(obj: JsonObject) = withContext(Dispatchers.IO) {
        try {
            synchronized(writer) {
                writer.write(obj.toString())
                writer.write("\n")
                writer.flush()
            }
        } catch (e: java.io.IOException) {
            throw AcpException(AcpException.PROCESS_EXITED, "Agent process is not running")
        }
    }

    /** Closes stdin so the agent can shut down cleanly (and stop its children), then kills it. */
    fun close() {
        runCatching { synchronized(writer) { writer.close() } }
        thread(isDaemon = true, name = "acp-close") {
            Thread.sleep(3_000)
            runCatching { process.destroy() }
        }
    }

    companion object {
        private const val TAG = "Acp"
        val json = Json { ignoreUnknownKeys = true }
    }
}
