package dev.yurlungr.app.chat

import dev.yurlungr.app.projects.McpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/** A project's MCP servers (`.mcp.json`) in the form ACP's `session/new` and `session/load` take. */
object McpServers {
    /** Remote transports the agent accepts (`agentCapabilities.mcpCapabilities`); stdio always works. */
    data class Caps(val http: Boolean = false, val sse: Boolean = false)

    fun caps(agentCapabilities: JsonObject?): Caps {
        val m = agentCapabilities?.get("mcpCapabilities") as? JsonObject ?: return Caps()
        return Caps(
            http = (m["http"] as? JsonPrimitive)?.booleanOrNull ?: false,
            sse = (m["sse"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    /**
     * stdio servers as `{name, command, args, env: [{name, value}]}`; URL servers as
     * `{type: "http", name, url, headers: []}` only when the agent advertises HTTP. Servers named
     * in [skip] (already in the agent's own settings) and ones without a command or URL are left
     * out. [resolve] turns a bare command into a path where it can (agents may not search PATH).
     */
    fun toAcp(servers: List<McpServer>, caps: Caps, skip: Set<String> = emptySet(), resolve: (String) -> String = { it }): JsonArray =
        buildJsonArray {
            for (s in servers) {
                if (s.name.isBlank() || s.name in skip) continue
                if (s.isRemote) {
                    if (!caps.http) continue
                    addJsonObject {
                        put("type", "http")
                        put("name", s.name)
                        put("url", s.url)
                        putJsonArray("headers") {}
                    }
                } else {
                    if (s.command.isBlank()) continue
                    addJsonObject {
                        put("name", s.name)
                        put("command", resolve(s.command))
                        putJsonArray("args") { s.args.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("env") {
                            s.env.forEach { (k, v) -> addJsonObject { put("name", k); put("value", v) } }
                        }
                    }
                }
            }
        }

    /** The server names in a settings file's `mcpServers` (Gemini's and Qwen's project settings). */
    fun namesIn(settings: File): Set<String> = runCatching {
        if (!settings.isFile) return emptySet()
        ((Json.parseToJsonElement(settings.readText()) as? JsonObject)?.get("mcpServers") as? JsonObject)?.keys.orEmpty()
    }.getOrDefault(emptySet())
}
