package dev.yurlungr.app.web

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

internal enum class ApiBodyType(val label: String) { None("No body"), Json("JSON"), Text("Text"), Form("Form") }

/** A request as the user writes it: headers one "Name: value" per line, form fields one "key=value" per line. */
internal data class ApiRequest(
    val name: String = "",
    val method: String = "GET",
    val url: String = "",
    val headers: String = "",
    val bodyType: ApiBodyType = ApiBodyType.None,
    val body: String = "",
)

/** What actually goes out. */
internal class BuiltRequest(val method: String, val url: String, val headers: List<Pair<String, String>>, val body: ByteArray?)

internal data class ApiResponse(
    val status: Int,
    val reason: String,
    val millis: Long,
    /** Bytes received (counting stops at [ApiRequests.MAX_COUNTED]). */
    val size: Long,
    val headers: List<Pair<String, String>>,
    val body: String,
    /** The body was longer than what's shown. */
    val truncated: Boolean,
    val contentType: String,
)

internal object ApiRequests {
    val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    const val MAX_SHOWN = 512 * 1024
    const val MAX_COUNTED = 50L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val TOTAL_TIMEOUT_MS = 60_000L
    private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    fun defaultUrl(port: Int?) = "http://127.0.0.1:${port ?: 3000}/"

    fun hasBody(method: String) = method.uppercase() !in setOf("GET", "HEAD")

    /** "Name: value" lines; blank lines and # comments are skipped. Throws with the line that's wrong. */
    fun parseHeaders(text: String): List<Pair<String, String>> = text.lines().mapIndexedNotNull { index, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith('#')) return@mapIndexedNotNull null
        val colon = line.indexOf(':')
        val name = if (colon > 0) line.substring(0, colon).trim() else ""
        require(TOKEN.matches(name)) { "Header line ${index + 1} should look like Name: value" }
        name to line.substring(colon + 1).trim()
    }

    /** "key=value" lines as application/x-www-form-urlencoded. */
    fun formEncode(text: String): String = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.joinToString("&") { line ->
        val key = line.substringBefore('=')
        val value = if ('=' in line) line.substringAfter('=') else ""
        URLEncoder.encode(key.trim(), "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
    }

    /** Checks [request] and turns it into what is sent: only http and https, a known method, valid headers. */
    fun build(request: ApiRequest): BuiltRequest {
        val method = request.method.trim().uppercase()
        require(method in METHODS) { "Choose a method." }
        val url = request.url.trim()
        val uri = runCatching { URI(url) }.getOrNull()
        require(uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "Enter an http:// or https:// address, like ${defaultUrl(null)}"
        }
        val headers = parseHeaders(request.headers).toMutableList()
        val body = when {
            !hasBody(method) || request.bodyType == ApiBodyType.None -> null
            request.bodyType == ApiBodyType.Form -> formEncode(request.body)
            else -> request.body
        }
        if (body != null && headers.none { it.first.equals("Content-Type", ignoreCase = true) }) {
            headers += "Content-Type" to when (request.bodyType) {
                ApiBodyType.Json -> "application/json"
                ApiBodyType.Form -> "application/x-www-form-urlencoded"
                else -> "text/plain; charset=utf-8"
            }
        }
        return BuiltRequest(method, url, headers, body?.toByteArray(Charsets.UTF_8))
    }

    /** The charset a Content-Type names, else UTF-8. */
    fun charsetOf(contentType: String): java.nio.charset.Charset =
        Regex("charset=\"?([\\w.:-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
            ?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8

    fun looksLikeJson(contentType: String, body: String): Boolean =
        contentType.contains("json", ignoreCase = true) || body.trimStart().let { it.startsWith('{') || it.startsWith('[') }

    /** Sends [request] and reads at most [MAX_SHOWN] bytes of the answer. Redirects are shown, not followed. */
    suspend fun send(request: BuiltRequest): ApiResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = request.method
            for ((name, value) in request.headers) connection.addRequestProperty(name, value)
            val started = System.nanoTime()
            request.body?.let { bytes ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = runCatching { if (status >= 400) connection.errorStream else connection.inputStream }.getOrNull()
            val shown = java.io.ByteArrayOutputStream()
            var size = 0L
            stream?.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (size < MAX_COUNTED) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (shown.size() < MAX_SHOWN) shown.write(buffer, 0, minOf(read, MAX_SHOWN - shown.size()))
                    size += read
                    check((System.nanoTime() - started) / 1_000_000 < TOTAL_TIMEOUT_MS) { "The response took over a minute; stopped reading." }
                }
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            val contentType = connection.contentType.orEmpty()
            val headers = connection.headerFields.orEmpty().flatMap { (name, values) -> if (name == null) emptyList() else values.map { name to it } }
            ApiResponse(status, connection.responseMessage.orEmpty(), millis, size, headers,
                String(shown.toByteArray(), charsetOf(contentType)), size > shown.size(), contentType)
        } finally {
            connection.disconnect()
        }
    }

    /** A friendlier word for what went wrong when a request couldn't be made at all. */
    fun describe(error: Throwable): String = when (error) {
        is java.net.ConnectException -> "Nothing answered. Is the server running on that port?"
        is java.net.SocketTimeoutException -> "No answer in time."
        is java.net.UnknownHostException -> "Couldn't find that host."
        else -> error.message?.takeIf { "cleartext" in it.lowercase() }?.let { "Plain http only works for 127.0.0.1 and localhost. Use https for other hosts." }
            ?: error.message ?: error.javaClass.simpleName
    }
}

/** Saved requests per project, in app-private storage (never in the project's own folder). */
internal object ApiRequestStore {
    /** A file name for a project path: its SHA-256, so any path maps to a safe, fixed-length name. */
    fun key(projectPath: String): String =
        MessageDigest.getInstance("SHA-256").digest(projectPath.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun encode(projectPath: String, requests: List<ApiRequest>): String = buildJsonObject {
        put("project", projectPath)
        put("requests", buildJsonArray {
            for (r in requests) add(buildJsonObject {
                put("name", r.name); put("method", r.method); put("url", r.url)
                put("headers", r.headers); put("bodyType", r.bodyType.name); put("body", r.body)
            })
        })
    }.toString()

    fun decode(text: String): List<ApiRequest> = runCatching {
        val root = Json.parseToJsonElement(text) as JsonObject
        (root["requests"] as JsonArray).mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            fun s(name: String) = (o[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
            ApiRequest(
                name = s("name"), method = s("method").ifBlank { "GET" }, url = s("url"), headers = s("headers"),
                bodyType = ApiBodyType.entries.firstOrNull { it.name == s("bodyType") } ?: ApiBodyType.None, body = s("body"),
            )
        }
    }.getOrDefault(emptyList())

    private fun file(context: Context, projectPath: String) = File(context.filesDir, "api-requests/${key(projectPath)}.json")

    fun load(context: Context, projectPath: String): List<ApiRequest> =
        runCatching { decode(file(context, projectPath).readText()) }.getOrDefault(emptyList())

    fun save(context: Context, projectPath: String, requests: List<ApiRequest>) {
        val target = file(context, projectPath)
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".tmp")
        temp.writeText(encode(projectPath, requests))
        if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
    }
}

/** A route from an OpenAPI (3.x) or Swagger (2.0) description. */
internal data class ApiRoute(val method: String, val path: String, val summary: String, val bodyType: ApiBodyType, val body: String)

/** Reads routes from OpenAPI / Swagger JSON: paths, methods and an example body where one can be made. Never fetches $refs elsewhere. */
internal object OpenApi {
    private val METHODS = listOf("get", "post", "put", "patch", "delete", "head", "options")
    private val pretty = Json { prettyPrint = true }
    private const val MAX_ROUTES = 500

    fun parse(text: String): List<ApiRoute> {
        val doc = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val paths = doc["paths"] as? JsonObject ?: return emptyList()
        val routes = mutableListOf<ApiRoute>()
        for ((path, item) in paths) {
            val operations = resolve(doc, item) as? JsonObject ?: continue
            for (method in METHODS) {
                val operation = operations[method] as? JsonObject ?: continue
                val summary = string(operation["summary"]) ?: string(operation["operationId"]) ?: string(operation["description"])?.lineSequence()?.firstOrNull().orEmpty()
                val (type, body) = exampleBody(doc, operation)
                routes += ApiRoute(method.uppercase(), path, summary.take(160), type, body)
                if (routes.size >= MAX_ROUTES) return routes
            }
        }
        return routes
    }

    /** The path prefix routes live under: OpenAPI 3's first server (its path part) or Swagger's basePath. */
    fun basePath(text: String): String {
        val doc = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return ""
        val server = ((doc["servers"] as? JsonArray)?.firstOrNull() as? JsonObject)?.let { string(it["url"]) }
        val raw = server?.let { url -> if (url.startsWith("/")) url else runCatching { URI(url).path }.getOrNull() } ?: string(doc["basePath"]) ?: ""
        return raw.trimEnd('/').takeIf { it.startsWith("/") && '{' !in it }.orEmpty()
    }

    private fun exampleBody(doc: JsonObject, operation: JsonObject): Pair<ApiBodyType, String> {
        // OpenAPI 3: requestBody.content by media type.
        (resolve(doc, operation["requestBody"]) as? JsonObject)?.let { requestBody ->
            val content = requestBody["content"] as? JsonObject ?: return ApiBodyType.None to ""
            val json = content.entries.firstOrNull { it.key.contains("json", ignoreCase = true) }?.value as? JsonObject
            if (json != null) {
                val example = json["example"]
                    ?: ((json["examples"] as? JsonObject)?.values?.firstOrNull()?.let { resolve(doc, it) as? JsonObject })?.get("value")
                    ?: example(doc, json["schema"], 0)
                return ApiBodyType.Json to (example?.let { pretty.encodeToString(JsonElement.serializer(), it) } ?: "{}")
            }
            (content["application/x-www-form-urlencoded"] ?: content["multipart/form-data"])?.let { form ->
                return ApiBodyType.Form to formLines(example(doc, (form as? JsonObject)?.get("schema"), 0))
            }
            if (content.keys.any { it.startsWith("text/") }) return ApiBodyType.Text to ""
            return ApiBodyType.None to ""
        }
        // Swagger 2: a body parameter, or formData parameters.
        val parameters = (operation["parameters"] as? JsonArray).orEmpty().mapNotNull { resolve(doc, it) as? JsonObject }
        parameters.firstOrNull { string(it["in"]) == "body" }?.let { parameter ->
            val example = example(doc, parameter["schema"], 0)
            return ApiBodyType.Json to (example?.let { pretty.encodeToString(JsonElement.serializer(), it) } ?: "{}")
        }
        val fields = parameters.filter { string(it["in"]) == "formData" }
        if (fields.isNotEmpty()) return ApiBodyType.Form to fields.joinToString("\n") { "${string(it["name"]).orEmpty()}=" }
        return ApiBodyType.None to ""
    }

    private fun formLines(example: JsonElement?): String = (example as? JsonObject).orEmpty().entries.joinToString("\n") { (key, value) ->
        "$key=" + ((value as? JsonPrimitive)?.contentOrNull ?: "")
    }

    /** An example value for [schema]: its own example or default, else one built from its type and properties. */
    fun example(doc: JsonObject, schema: JsonElement?, depth: Int): JsonElement? {
        if (depth > 6) return null
        val s = resolve(doc, schema) as? JsonObject ?: return null
        s["example"]?.let { return it }
        s["default"]?.let { return it }
        (s["enum"] as? JsonArray)?.firstOrNull()?.let { return it }
        (s["allOf"] as? JsonArray)?.let { parts ->
            val merged = parts.mapNotNull { example(doc, it, depth + 1) as? JsonObject }.flatMap { it.entries }.associate { it.key to it.value }
            return JsonObject(merged)
        }
        ((s["oneOf"] ?: s["anyOf"]) as? JsonArray)?.firstOrNull()?.let { return example(doc, it, depth + 1) }
        val type = when (val t = s["type"]) {
            is JsonArray -> t.firstNotNullOfOrNull { string(it)?.takeIf { name -> name != "null" } }
            else -> string(t)
        } ?: if (s["properties"] != null) "object" else null
        return when (type) {
            "object" -> JsonObject((s["properties"] as? JsonObject).orEmpty().entries.take(40)
                .associate { (name, property) -> name to (example(doc, property, depth + 1) ?: JsonNull) })
            "array" -> JsonArray(listOfNotNull(example(doc, s["items"], depth + 1)))
            "string" -> JsonPrimitive(when (string(s["format"])) {
                "date-time" -> "2024-01-01T00:00:00Z"; "date" -> "2024-01-01"; "email" -> "user@example.com"
                "uuid" -> "00000000-0000-0000-0000-000000000000"; "uri", "url" -> "https://example.com"
                else -> "string"
            })
            "integer" -> JsonPrimitive(0)
            "number" -> JsonPrimitive(0.0)
            "boolean" -> JsonPrimitive(false)
            else -> null
        }
    }

    /** Follows a local "#/…" \$ref (a few levels, so cycles end); anything else is returned as is. */
    private fun resolve(doc: JsonObject, element: JsonElement?): JsonElement? {
        var current = element
        repeat(8) {
            val ref = ((current as? JsonObject)?.get("\$ref") as? JsonPrimitive)?.contentOrNull ?: return current
            if (!ref.startsWith("#/")) return null
            var node: JsonElement? = doc
            for (part in ref.removePrefix("#/").split('/')) {
                node = (node as? JsonObject)?.get(part.replace("~1", "/").replace("~0", "~")) ?: return null
            }
            current = node
        }
        return null
    }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** OpenAPI / Swagger JSON files in a project: shallow, skipping dependency and build folders. Blocking. */
    fun findSpecs(root: File): List<File> {
        val found = mutableListOf<File>()
        val name = Regex("^(openapi|swagger|api-docs)([._-][\\w.-]*)?\\.json$", RegexOption.IGNORE_CASE)
        fun walk(dir: File, depth: Int) {
            if (depth > 4 || found.size >= 20) return
            for (child in dir.listFiles()?.sortedBy { it.name }.orEmpty()) {
                if (child.isDirectory) { if (!FileWatch.ignoredDir(child.name)) walk(child, depth + 1) }
                else if (name.matches(child.name) && child.length() in 1..MAX_SPEC_BYTES) found += child
            }
        }
        walk(root, 0)
        return found
    }

    const val MAX_SPEC_BYTES = 4L * 1024 * 1024
    /** Where frameworks usually serve their description (FastAPI, Spring, Express swagger tools…). */
    val SERVER_PATHS = listOf("/openapi.json", "/swagger.json", "/v3/api-docs", "/api-docs", "/api/openapi.json")

    /** The first description the server at [base] serves at one of [SERVER_PATHS], or null. Blocking. */
    fun fetchFromServer(base: String): Pair<String, String>? {
        for (path in SERVER_PATHS) {
            val text = runCatching {
                val connection = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 3_000; connection.readTimeout = 10_000
                    connection.instanceFollowRedirects = false
                    if (connection.responseCode != 200) null
                    else connection.inputStream.use { input ->
                        val bytes = input.readNBytesCompat(MAX_SPEC_BYTES.toInt() + 1)
                        if (bytes.size > MAX_SPEC_BYTES) null else String(bytes, Charsets.UTF_8)
                    }
                } finally { connection.disconnect() }
            }.getOrNull() ?: continue
            if (runCatching { (Json.parseToJsonElement(text) as? JsonObject)?.containsKey("paths") }.getOrNull() == true) return path to text
        }
        return null
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

