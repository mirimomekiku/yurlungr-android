package dev.yurlungr.app.acp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** JSON-RPC framing over a fake agent process, and closing it. */
class AcpConnectionTest {
    /** An "agent" whose stdout we write and whose stdin we read, over pipes. */
    private class PipeProcess : Process() {
        val toAgent = PipedInputStream(1 shl 16)
        private val agentStdin = PipedOutputStream(toAgent)
        val fromAgent = PipedOutputStream()
        private val agentStdout = PipedInputStream(fromAgent, 1 shl 16)
        val destroyed = CountDownLatch(1)
        private val done = CountDownLatch(1)
        override fun getOutputStream(): OutputStream = agentStdin
        override fun getInputStream(): InputStream = agentStdout
        override fun getErrorStream(): InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { done.await(); return 0 }
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed.countDown()
            runCatching { fromAgent.close() }
            done.countDown()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private val handler = object : AcpConnection.Handler {
        val notes = mutableListOf<String>()
        override suspend fun onRequest(method: String, params: JsonObject): JsonElement = buildJsonObject { put("echo", method) }
        override fun onNotification(method: String, params: JsonObject) { synchronized(notes) { notes += method } }
    }

    @Test
    fun `requests get their responses and agent requests are answered`() = runBlocking {
        val p = PipeProcess()
        val conn = AcpConnection(p, scope, handler, closeGraceMs = 0)
        val agentIn = p.toAgent.bufferedReader()
        val agentOut = p.fromAgent.bufferedWriter()
        val pending = scope.async { conn.request("initialize", buildJsonObject { put("protocolVersion", 1) }) }
        val request = kotlinx.serialization.json.Json.parseToJsonElement(agentIn.readLine()).jsonObject
        assertEquals("initialize", request["method"]!!.jsonPrimitive.content)
        val id = request["id"]!!.jsonPrimitive.content
        // A notification before the response is handled first; then an agent → client request.
        agentOut.write("""{"jsonrpc":"2.0","method":"session/update","params":{}}""" + "\n")
        agentOut.write("""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""" + "\n")
        agentOut.write("""{"jsonrpc":"2.0","id":"x7","method":"fs/read_text_file","params":{}}""" + "\n")
        agentOut.flush()
        val result = withTimeout(5_000) { pending.await() }
        assertEquals("true", result.jsonObject["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("session/update"), synchronized(handler.notes) { handler.notes.toList() })
        val answer = kotlinx.serialization.json.Json.parseToJsonElement(agentIn.readLine()).jsonObject
        assertEquals("x7", answer["id"]!!.jsonPrimitive.content)
        assertEquals("fs/read_text_file", answer["result"]!!.jsonObject["echo"]!!.jsonPrimitive.content)

        // Closing ends stdin (the agent sees EOF) and then stops the process.
        conn.close()
        assertEquals(null, agentIn.readLine())
        assertTrue(p.destroyed.await(5, TimeUnit.SECONDS))
    }

}
