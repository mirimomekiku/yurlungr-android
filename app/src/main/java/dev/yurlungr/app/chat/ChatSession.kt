package dev.yurlungr.app.chat

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.yurlungr.app.acp.AcpConnection
import dev.yurlungr.app.acp.AcpException
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.runtime.RuntimeEnv
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * One conversation with one agent on this phone: owns the agent process and turns ACP
 * traffic into [ChatItem]s. All state is mutated on the main thread, and every mutation is
 * reported to [listeners] so the chat can be mirrored to another device.
 */
class ChatSession(
    context: Context,
    override val agent: Agent,
    val cwd: File,
    restored: SavedChat? = null,
    skipPermissions: Boolean = restored?.skipPermissions ?: false,
) : ChatModel, AcpConnection.Handler {
    override val id: String = restored?.id ?: UUID.randomUUID().toString()
    private val appContext = context.applicationContext
    private val paths = RuntimePaths(appContext)
    private val clientVersion = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
    // The handler is a backstop: an unexpected exception in agent traffic is logged, not fatal.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unhandled error in chat", e) },
    )

    val listeners = mutableSetOf<ChatListener>()

    override val items = mutableStateListOf<ChatItem>().apply { restored?.items?.let(::addAll) }
    override val cwdLabel: String get() = cwdLabel(cwd.path)
    override val folderName: String get() = cwd.name
    override val isRemote = false
    override var skipPermissions by mutableStateOf(skipPermissions)
        private set

    private var _state by mutableStateOf<ChatState>(ChatState.Saved)
    override var state: ChatState
        get() = _state
        private set(value) {
            if (_state == value) return
            _state = value
            listeners.forEach { it.onStateChanged(this) }
        }

    private var _title by mutableStateOf(restored?.title ?: "${agent.displayName} · ${cwd.name}")
    override var title: String
        get() = _title
        private set(value) {
            _title = value
            meta()
        }
    private var _modes by mutableStateOf<List<SessionMode>>(emptyList())
    override var modes: List<SessionMode>
        get() = _modes
        private set(value) {
            _modes = value
            meta()
        }
    private var _currentModeId by mutableStateOf<String?>(null)
    override var currentModeId: String?
        get() = _currentModeId
        private set(value) {
            _currentModeId = value
            meta()
        }
    /** A sign-in URL the agent printed, if any. */
    private var _loginUrl by mutableStateOf<String?>(null)
    override var loginUrl: String?
        get() = _loginUrl
        private set(value) {
            _loginUrl = value
            meta()
        }

    private var _commands by mutableStateOf<List<SlashCommand>>(emptyList())
    override var commands: List<SlashCommand>
        get() = _commands
        private set(value) {
            _commands = value
            meta()
        }
    private var _configOptions by mutableStateOf<List<ConfigOption>>(emptyList())
    override var configOptions: List<ConfigOption>
        get() = _configOptions
        private set(value) {
            _configOptions = value
            meta()
        }
    private var _usage by mutableStateOf<Usage?>(null)
    override var usage: Usage?
        get() = _usage
        private set(value) {
            _usage = value
            meta()
        }
    private var _caps by mutableStateOf(PromptCaps())
    override var caps: PromptCaps
        get() = _caps
        private set(value) {
            _caps = value
            meta()
        }

    var updatedAt by mutableStateOf(restored?.updatedAt ?: System.currentTimeMillis())
        private set

    /** The agent-side session to resume with `session/load` after a restart. */
    private var acpSessionId: String? = restored?.acpSessionId
    /** While loading a saved session the agent replays history we already show; drop it. */
    private var replaying = false

    /**
     * Stop was pressed during this turn. Checked just before `session/prompt` goes out: a Stop
     * during the checkpoint snapshot (before there's a prompt to cancel) must still stop the turn.
     */
    @Volatile private var stopRequested = false

    /** A pending save of the transcript while a turn streams (see [saveSoon]). */
    private var saveJob: kotlinx.coroutines.Job? = null

    /** Persist hook (set by the registry). */
    var onChanged: ((ChatSession) -> Unit)? = null
    /** Process started/stopped (the registry keeps the foreground service in sync). */
    var onActiveChanged: ((ChatSession) -> Unit)? = null
    /** Called with the finished turn's summary / what needs approval (for notifications). */
    var onTurnFinished: ((ChatSession, String) -> Unit)? = null
    var onNeedsAttention: ((ChatSession, String) -> Unit)? = null
    /** No permission prompt is waiting any more (clears the "needs you" notification). */
    var onAttentionResolved: ((ChatSession) -> Unit)? = null

    /** An agent process is (or should be) running for this chat. */
    val isActive: Boolean get() = connection?.exited == false

    /** Last stderr lines, shown when the agent fails to start. */
    private val stderrTail = ArrayDeque<String>()
    private var connection: AcpConnection? = null

    /** JAVA_HOME the agent process started with (a JDK switch applies from the next start). */
    @Volatile var launchedJavaHome: String? = null
        private set

    /** An agent process is running for this chat. */
    val isLive: Boolean get() = connection != null
    private var sessionId: String? = null
    private val toolCalls = HashMap<String, ChatItem.ToolCall>()

    /**
     * Bumped whenever the agent process is torn down on purpose (stop, restart, delete), so
     * failures from the old process can't overwrite the state of what replaced it.
     */
    private var generation = 0
    private var startJob: Job? = null
    private var promptJob: Job? = null

    // ---- lifecycle ---------------------------------------------------------------------

    fun start() {
        val gen = ++generation
        state = ChatState.Starting
        toolCalls.clear()
        stderrTail.clear()
        startJob = scope.launch {
            // Set as soon as the process exists, so a cancel before the connection owns it can't leak it.
            val spawned = AtomicReference<Process?>(null)
            var conn: AcpConnection? = null
            try {
                // Also during a reinstall, which is about to replace everything the agent would run from.
                check(paths.isBootstrapInstalled) { "The Linux environment isn't installed (or is being reinstalled). Open System checks." }
                val hasHistory = items.isNotEmpty() || acpSessionId != null
                if (!cwd.isDirectory) {
                    // An existing chat whose project was deleted or moved: recreating an empty folder
                    // would let the agent work (and write) somewhere the user no longer expects.
                    if (hasHistory) {
                        state = ChatState.Failed("The project folder ${cwdLabel(cwd.path)} no longer exists. Restore it, or start a new chat.")
                        return@launch
                    }
                    check(cwd.mkdirs() || cwd.isDirectory) { "Couldn't create the project folder ${cwd.path}" }
                }
                val process = withContext(Dispatchers.IO) {
                    // Package profile scripts (JAVA_HOME…) first, silently: stdout is the agent's protocol.
                    ProcessBuilder(paths.bash.path, "-c", "${dev.yurlungr.app.runtime.RuntimeEnv.PROFILE_SNIPPET}; exec ${agent.launchCommand(skipPermissions)}")
                        .directory(cwd)
                        .apply {
                            environment().run {
                                clear()
                                putAll(RuntimeEnv.map(paths, cwd))
                                putAll(agent.env(paths))
                                launchedJavaHome = get("JAVA_HOME")
                            }
                        }
                        .start()
                        .also { spawned.set(it) }
                }
                conn = AcpConnection(process, scope, this@ChatSession)
                connection = conn
                onActiveChanged?.invoke(this@ChatSession)
                val init = conn.request("initialize", buildJsonObject {
                    put("protocolVersion", 1)
                    putJsonObject("clientCapabilities") {
                        putJsonObject("fs") {
                            put("readTextFile", true)
                            put("writeTextFile", true)
                        }
                        put("terminal", false)
                    }
                    putJsonObject("clientInfo") {
                        put("name", "yurlungr")
                        put("title", "Yurlungr")
                        put("version", clientVersion)
                    }
                })
                val agentCaps = (init as? JsonObject)?.get("agentCapabilities") as? JsonObject
                caps = AcpExtras.parseCaps(agentCaps)
                val canLoad = agentCaps?.get("loadSession")?.jsonPrimitive?.contentOrNull == "true"
                val previous = acpSessionId
                val session = if (previous != null && canLoad) loadOrNew(conn, previous) else newSession(conn)
                readModes(session["modes"] as? JsonObject)
                session["configOptions"]?.let { configOptions = AcpExtras.parseConfigOptions(it) }
                if (skipPermissions) applySkipPermissions(conn)
                state = ChatState.Ready
                changed()
                flushPending()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AcpException) {
                if (gen != generation) return@launch
                state = if (e.code == AcpException.AUTH_REQUIRED) ChatState.AuthRequired else ChatState.Failed(failureText(e))
            } catch (e: Exception) {
                if (gen != generation) return@launch
                Log.e(TAG, "start failed", e)
                state = ChatState.Failed(failureText(e))
            } finally {
                // Cancelled (stop/delete) between spawning and handing the process to a connection.
                if (conn == null) spawned.get()?.destroy()
            }
        }
    }

    /** Ends the running start/prompt work and makes any late failures from it stale. */
    private fun abandonWork() {
        generation++
        startJob?.cancel()
        startJob = null
        promptJob?.cancel()
        promptJob = null
    }

    /** Open permission prompts can no longer be answered: mark them cancelled. */
    private fun resolvePendingPermissions() {
        val open = items.filterIsInstance<ChatItem.Permission>().filter { it.resolved == null }
        open.forEach {
            it.resolved = "Cancelled"
            it.decision.complete(null)
            updated(it)
        }
        if (open.isNotEmpty()) onAttentionResolved?.invoke(this)
    }

    private suspend fun applySkipPermissions(conn: AcpConnection) {
        if (agent == Agent.Antigravity) return // applied by its documented ACP launch flag
        val mode = PermissionModes.skipMode(modes)
        if (mode != null) {
            conn.request("session/set_mode", buildJsonObject {
                put("sessionId", sessionId)
                put("modeId", mode.id)
            })
            currentModeId = mode.id
            return
        }
        val option = configOptions.firstOrNull { it.category == "mode" || it.id == "mode" }
        val choice = option?.choices?.firstOrNull { PermissionModes.isSkipMode(it.value) }
        if (option != null && choice != null) {
            val response = conn.request("session/set_config_option", buildJsonObject {
                put("sessionId", sessionId)
                put("configId", option.id)
                put("value", choice.value)
            }) as? JsonObject
            response?.get("configOptions")?.let { configOptions = AcpExtras.parseConfigOptions(it) }
            return
        }
        // Never send a queued prompt under a different policy than the one requested.
        error("${agent.displayName} doesn't advertise a skip-permissions mode. Start a chat with Agent default permissions, or update the agent.")
    }

    private suspend fun newSession(conn: AcpConnection): JsonObject {
        val session = conn.request("session/new", buildJsonObject {
            put("cwd", cwd.path)
            putJsonArray("mcpServers") {}
        }).jsonObject
        sessionId = session.string("sessionId")
        acpSessionId = sessionId
        return session
    }

    /** Resumes the agent's own session (keeps its context); falls back to a new one. */
    private suspend fun loadOrNew(conn: AcpConnection, previous: String): JsonObject {
        replaying = true
        return try {
            val session = conn.request("session/load", buildJsonObject {
                put("sessionId", previous)
                put("cwd", cwd.path)
                putJsonArray("mcpServers") {}
            }) as? JsonObject ?: JsonObject(emptyMap())
            sessionId = previous
            session
        } catch (e: AcpException) {
            if (e.code == AcpException.AUTH_REQUIRED || conn.exited) throw e
            add(ChatItem.Notice("Couldn't restore ${agent.displayName}'s memory of this chat; it starts fresh from here."))
            newSession(conn)
        } finally {
            replaying = false
        }
    }

    /** Opens the agent process for a saved chat (no-op if it's already running). */
    override fun resume() {
        if (state == ChatState.Saved) start()
    }

    /** Tears down the agent and starts again (e.g. after signing in). Keeps the saved session id. */
    override fun restart() {
        abandonWork()
        resolvePendingPermissions()
        connection?.close()
        connection = null
        sessionId = null
        loginUrl = null
        start()
    }

    /** Ends the agent process but keeps the chat (it can be resumed later). */
    fun stop() {
        cancel()
        abandonWork()
        resolvePendingPermissions() // also when there was no session to cancel yet
        connection?.close()
        connection = null
        sessionId = null
        state = ChatState.Saved
        changed()
        onActiveChanged?.invoke(this)
    }

    override fun endSession() = stop()

    override fun delete() = ChatSessions.delete(appContext, this)

    /** Ends the process for good (chat deleted). */
    fun close() {
        cancel()
        abandonWork()
        resolvePendingPermissions()
        connection?.close()
        connection = null
        scope.cancel()
        listeners.clear()
        onActiveChanged?.invoke(this)
    }

    /** Snapshot for [ChatStore]; call on the main thread. */
    fun snapshot() = SavedChat(
        id = id,
        agent = agent.name,
        cwd = cwd.path,
        title = title,
        acpSessionId = acpSessionId,
        updatedAt = updatedAt,
        items = items.toList(),
        skipPermissions = skipPermissions,
        pendingText = pendingPrompt?.first,
    )

    // ---- user actions ------------------------------------------------------------------

    /** First message of a new chat: shown now, sent as soon as the agent is ready (even after sign-in). */
    // Restored without attachments: those lived only in memory.
    private var pendingPrompt: Pair<String, List<Attachment>>? = restored?.pendingText?.let { it to emptyList() }

    fun queue(text: String, attachments: List<Attachment>) {
        if (text.isBlank() && attachments.isEmpty()) return
        showUserMessage(text, attachments)
        pendingPrompt = text to attachments
        changed()
        if (state == ChatState.Ready) flushPending()
    }

    private fun flushPending() {
        val (text, attachments) = pendingPrompt ?: return
        pendingPrompt = null
        prompt(text, attachments)
    }

    private fun showUserMessage(text: String, attachments: List<Attachment>) {
        // Like ChatGPT: the first message names the chat (an agent-provided title still wins later).
        if (items.none { it is ChatItem.User } && title == defaultTitle) {
            title = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(48)
                ?: attachments.firstOrNull()?.name ?: title
        }
        add(ChatItem.User(text.trim(), attachments.map { it.name }))
    }

    private val defaultTitle get() = "${agent.displayName} · ${cwd.name}"

    override val queued = mutableStateListOf<QueuedMessage>()

    override fun enqueue(text: String, attachments: List<Attachment>) {
        if (text.isBlank() && attachments.isEmpty()) return
        if (state == ChatState.Ready) send(text, attachments)
        else if (state == ChatState.Running || state == ChatState.Starting) queued += QueuedMessage(text, attachments)
    }

    override fun removeQueued(index: Int) {
        if (index in queued.indices) queued.removeAt(index)
    }

    override fun sendQueuedNow() {
        if (queued.isEmpty()) return
        if (state == ChatState.Running) {
            // The turn's end sends the queue (a stop normally holds it back).
            resumeQueueAfterStop = true
            cancel()
        } else drainQueue()
    }

    private var resumeQueueAfterStop = false

    /** Sends the oldest queued message if the agent is idle. */
    private fun drainQueue() {
        if (state != ChatState.Ready || queued.isEmpty()) return
        val next = queued.removeAt(0)
        send(next.text, next.attachments)
    }

    override suspend fun suggestFiles(query: String): List<String> = withContext(Dispatchers.IO) {
        val q = query.lowercase()
        val skip = setOf(".git", "node_modules", "build", ".gradle", ".idea", "dist", "target", ".next", "__pycache__", ".venv")
        val out = ArrayList<String>()
        fun walk(dir: File, depth: Int) {
            if (out.size >= 400 || depth > 6) return
            for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (f.name in skip || f.name.startsWith(".") && f.name != ".github") continue
                if (f.isDirectory) walk(f, depth + 1)
                else {
                    val rel = f.relativeTo(cwd).path
                    if (q.isEmpty() || rel.lowercase().contains(q)) out += rel
                }
            }
        }
        walk(cwd, 0)
        out.sortedWith(compareBy({ !it.substringAfterLast('/').lowercase().startsWith(q) }, { it.length })).take(8)
    }

    override fun send(text: String, attachments: List<Attachment>) {
        if (state != ChatState.Ready || (text.isBlank() && attachments.isEmpty())) return
        showUserMessage(text, attachments)
        prompt(text, attachments)
    }

    private fun prompt(text: String, attachments: List<Attachment>) {
        val conn = connection ?: return
        val sid = sessionId ?: return
        val gen = generation
        stopRequested = false
        state = ChatState.Running
        changed()
        promptJob = scope.launch {
            var turn: ChatTurn? = null
            try {
                // A checkpoint of the project before the agent works, so this turn can be undone.
                turn = runCatching { Checkpoints.begin(appContext, id, cwd, text) }.getOrNull()
                val blocks = withContext(Dispatchers.IO) { Attachments.toPromptBlocks(cwd, attachments, caps) }
                if (stopRequested) {
                    // Stopped before it was sent: the agent never sees this turn.
                    if (gen == generation) {
                        add(ChatItem.Notice("Stopped before sending"))
                        state = ChatState.Ready
                    }
                    return@launch
                }
                val result = conn.request("session/prompt", buildJsonObject {
                    put("sessionId", sid)
                    put("prompt", buildJsonArray {
                        // ACP requires at least one block; a text block also gives the agent context for files.
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text.trim().ifEmpty { "See the attached file(s)." })
                        })
                        blocks.forEach { add(it) }
                    })
                }) as? JsonObject ?: JsonObject(emptyMap())
                if (gen != generation) return@launch
                when (val reason = result.string("stopReason")) {
                    "end_turn", null -> Unit
                    "cancelled" -> add(ChatItem.Notice("Stopped"))
                    else -> add(ChatItem.Notice("Turn ended: ${reason.replace('_', ' ')}"))
                }
                state = ChatState.Ready
                onTurnFinished?.invoke(this@ChatSession, lastAgentText())
                // Queued messages go out in order, unless the user pressed Stop (then they wait for "Send now").
                if (result.string("stopReason") != "cancelled" || resumeQueueAfterStop) {
                    resumeQueueAfterStop = false
                    drainQueue()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AcpException) {
                // Ended or restarted on purpose: the old process's failure isn't this chat's state.
                if (gen != generation) return@launch
                state = when {
                    // The sign-in card explains this; no need for an error notice (or the agent's debug log).
                    e.code == AcpException.AUTH_REQUIRED -> ChatState.AuthRequired
                    conn.exited -> ChatState.Failed(failureText(e))
                    else -> {
                        add(ChatItem.Notice(e.message ?: "Request failed", isError = true))
                        ChatState.Ready
                    }
                }
                // Not delivered: resend automatically once the user has signed in and the agent restarts.
                if (state == ChatState.AuthRequired) pendingPrompt = text to attachments
            } catch (e: Exception) {
                // E.g. attachments couldn't be written (disk full, read-only folder).
                if (gen != generation) return@launch
                Log.e(TAG, "prompt failed", e)
                add(ChatItem.Notice("Couldn't send: ${e.message ?: e.toString()}", isError = true))
                state = ChatState.Ready
            } finally {
                // And after, whatever happened (done, stopped, failed): what the turn changed.
                turn?.let { t -> withContext(kotlinx.coroutines.NonCancellable) { runCatching { Checkpoints.end(appContext, id, cwd, t) } } }
                saveJob?.cancel()
                changed()
            }
        }
    }

    override fun cancel() {
        val conn = connection ?: return
        val sid = sessionId ?: return
        if (state == ChatState.Running) stopRequested = true
        // Pending permission prompts are answered "cancelled" as the protocol requires.
        resolvePendingPermissions()
        scope.launch {
            runCatching { conn.notify("session/cancel", buildJsonObject { put("sessionId", sid) }) }
        }
    }

    override fun setMode(modeId: String) {
        val conn = connection ?: return
        val sid = sessionId ?: return
        scope.launch {
            try {
                conn.request("session/set_mode", buildJsonObject {
                    put("sessionId", sid)
                    put("modeId", modeId)
                })
                currentModeId = modeId
                if (agent != Agent.Antigravity) skipPermissions = PermissionModes.isSkipMode(modeId)
                meta()
                changed()
            } catch (e: AcpException) {
                add(ChatItem.Notice("Couldn't switch mode: ${e.message}", isError = true))
            }
        }
    }

    override fun setConfigOption(id: String, value: String) = setConfig(id, kotlinx.serialization.json.JsonPrimitive(value))
    override fun setConfigOption(id: String, value: Boolean) = setConfig(id, kotlinx.serialization.json.JsonPrimitive(value))

    private fun setConfig(id: String, value: JsonPrimitive) {
        val conn = connection ?: return
        val sid = sessionId ?: return
        val isPermissionMode = configOptions.any { it.id == id && (it.category == "mode" || it.id == "mode") }
        scope.launch {
            try {
                val result = conn.request("session/set_config_option", buildJsonObject {
                    put("sessionId", sid)
                    put("configId", id)
                    if (value.isString) put("value", value.content) else put("value", value)
                    if (!value.isString) put("type", "boolean")
                }) as? JsonObject
                result?.get("configOptions")?.let { configOptions = AcpExtras.parseConfigOptions(it) }
                if (agent != Agent.Antigravity && isPermissionMode && value.isString) {
                    skipPermissions = PermissionModes.isSkipMode(value.content)
                    meta()
                    changed()
                }
            } catch (e: AcpException) {
                add(ChatItem.Notice("Couldn't change setting: ${e.message}", isError = true))
            }
        }
    }

    override fun alwaysAllow(item: ChatItem.Permission) {
        val kind = item.toolKind ?: return
        ApprovalRules.add(appContext, ApprovalRule(cwd.path, kind))
        val option = item.options.firstOrNull { it.kind == "allow_once" } ?: item.options.firstOrNull { it.isAllow } ?: return
        answerPermission(item, option)
    }

    override fun answerPermission(item: ChatItem.Permission, option: PermissionOption) {
        if (item.resolved != null) return
        item.resolved = option.name
        item.decision.complete(option.optionId)
        updated(item)
        changed()
        if (items.none { it is ChatItem.Permission && it.resolved == null }) onAttentionResolved?.invoke(this)
    }

    /** Answers a permission prompt identified by its item key (from a remote device). */
    fun answerPermission(itemKey: Long, optionId: String) {
        val item = items.firstOrNull { it.key == itemKey } as? ChatItem.Permission ?: return
        val option = item.options.firstOrNull { it.optionId == optionId } ?: return
        answerPermission(item, option)
    }

    // ---- ACP handler -------------------------------------------------------------------

    override suspend fun onRequest(method: String, params: JsonObject): JsonElement = when (method) {
        "session/request_permission" -> requestPermission(params)
        "fs/read_text_file" -> readTextFile(params)
        "fs/write_text_file" -> writeTextFile(params)
        else -> throw AcpException(AcpException.METHOD_NOT_FOUND, "Method not supported: $method")
    }

    override fun onNotification(method: String, params: JsonObject) {
        if (method != "session/update") return
        val update = params["update"] as? JsonObject ?: return
        val kind = update.string("sessionUpdate")
        // session/load replays the whole history; the saved transcript already shows it.
        // Mode, commands and settings still apply to the resumed session.
        if (replaying && kind !in setOf("current_mode_update", "available_commands_update", "config_option_update")) return
        when (kind) {
            "agent_message_chunk" -> appendChunk(update, thought = false)
            "agent_thought_chunk" -> appendChunk(update, thought = true)
            "tool_call", "tool_call_update" -> upsertToolCall(update)
            "plan" -> updatePlan(update)
            "current_mode_update" -> currentModeId = update.string("currentModeId") ?: currentModeId
            "session_info_update" -> update.string("title")?.let { title = it }
            "notice" -> (update.string("message") ?: update.string("text"))?.let { add(ChatItem.Notice(it)) }
            "available_commands_update" -> commands = AcpExtras.parseCommands(update["availableCommands"])
            "config_option_update" -> configOptions = AcpExtras.parseConfigOptions(update["configOptions"])
            "usage_update" -> AcpExtras.parseUsage(update)?.let { usage = it }
            // user_message_chunk is a replay of our own messages: already shown.
        }
    }

    override fun onStderr(line: String) {
        stderrTail.addLast(line)
        while (stderrTail.size > 30) stderrTail.removeFirst()
        // Some CLIs print a sign-in URL instead of opening a browser; offer it as a button.
        if (LOGIN_HINT.containsMatchIn(line)) URL_REGEX.find(line)?.let { loginUrl = it.value }
    }

    override fun onExit(code: Int) {
        // Only react if the *current* connection died (not one replaced by restart()).
        if (connection?.exited != true) return
        resolvePendingPermissions()
        if (state != ChatState.AuthRequired) state = ChatState.Failed("Agent exited (code $code)${stderrSuffix()}")
        onActiveChanged?.invoke(this)
    }

    // ---- update handling ---------------------------------------------------------------

    private fun add(item: ChatItem) {
        items += item
        listeners.forEach { it.onItemAdded(this, item) }
    }

    private fun updated(item: ChatItem) = listeners.forEach { it.onItemUpdated(this, item) }

    private fun meta() = listeners.forEach { it.onMetaChanged(this) }

    private fun appendChunk(update: JsonObject, thought: Boolean) {
        val content = update["content"] as? JsonObject ?: return
        val text = when (content.string("type")) {
            "text" -> content.string("text")
            "resource_link" -> content.string("uri")?.let { "[$it]" }
            "image" -> "[image]"
            else -> null
        } ?: return
        val messageId = update.string("messageId")
        val last = items.lastOrNull()
        val item: ChatItem = if (thought) {
            (last as? ChatItem.Thought)?.takeIf { it.messageId == messageId }
                ?: ChatItem.Thought(messageId).also(::add)
        } else {
            (last as? ChatItem.AgentText)?.takeIf { it.messageId == messageId }
                ?: ChatItem.AgentText(messageId).also(::add)
        }
        when (item) {
            is ChatItem.Thought -> item.text += text
            is ChatItem.AgentText -> item.text += text
            else -> return
        }
        listeners.forEach { it.onTextAppended(this, item, text) }
        saveSoon()
    }

    private fun upsertToolCall(update: JsonObject) {
        val id = update.string("toolCallId") ?: return
        val existing = toolCalls[id]
        val item = existing ?: ChatItem.ToolCall(id).also { toolCalls[id] = it }
        update.string("title")?.let { item.title = it }
        update.string("kind")?.let { item.kind = it }
        update.string("status")?.let { item.status = it }
        (update["content"] as? JsonArray)?.let { arr -> item.content = arr.mapNotNull(::parseToolContent) }
        (update["locations"] as? JsonArray)?.let { arr ->
            item.locations = arr.mapNotNull { loc ->
                val obj = loc as? JsonObject ?: return@mapNotNull null
                val path = obj.string("path") ?: return@mapNotNull null
                (obj["line"] as? JsonPrimitive)?.intOrNull?.let { "$path:$it" } ?: path
            }
        }
        if (existing == null) add(item) else updated(item)
        saveSoon()
    }

    private fun parseToolContent(el: JsonElement): ToolContent? {
        val obj = el as? JsonObject ?: return null
        return when (obj.string("type")) {
            "content" -> (obj["content"] as? JsonObject)?.string("text")?.let { ToolContent.Text(it) }
            "diff" -> ToolContent.Diff(obj.string("path") ?: "?", obj.string("oldText"), obj.string("newText") ?: "")
            "terminal" -> obj.string("terminalId")?.let { ToolContent.Terminal(it) }
            else -> null
        }
    }

    private fun updatePlan(update: JsonObject) {
        val entries = (update["entries"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            PlanEntry(obj.string("content") ?: "", obj.string("status") ?: "pending", obj.string("priority") ?: "medium")
        } ?: return
        // Keep one live plan card per run of updates; a new plan after other output gets a new card.
        val existing = items.lastOrNull { it is ChatItem.Plan || it is ChatItem.User } as? ChatItem.Plan
        val plan = existing ?: ChatItem.Plan()
        plan.entries = entries
        if (existing == null) add(plan) else updated(plan)
        saveSoon()
    }

    private fun readModes(obj: JsonObject?) {
        obj ?: return
        modes = (obj["availableModes"] as? JsonArray)?.mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            SessionMode(m.string("id") ?: return@mapNotNull null, m.string("name") ?: "?", m.string("description"))
        }.orEmpty()
        currentModeId = obj.string("currentModeId")
    }

    private suspend fun requestPermission(params: JsonObject): JsonElement {
        val toolCall = params["toolCall"] as? JsonObject
        toolCall?.let(::upsertToolCall)
        val toolTitle = toolCall?.string("title")
            ?: toolCall?.string("toolCallId")?.let { toolCalls[it]?.title }
            ?: "Tool call"
        val options = (params["options"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            PermissionOption(o.string("optionId") ?: return@mapNotNull null, o.string("name") ?: "?", o.string("kind") ?: "")
        }.orEmpty()
        val toolKind = toolCall?.string("kind") ?: toolCall?.string("toolCallId")?.let { toolCalls[it]?.kind }
        // A standing rule for this kind of tool call in this project: approve once, quietly, and say so.
        val auto = options.firstOrNull { it.kind == "allow_once" } ?: options.firstOrNull { it.isAllow }
        if (auto != null && ApprovalRules.matches(appContext, cwd.path, toolKind)) {
            add(ChatItem.Notice("Auto-approved: $toolTitle (rule: allow ${ApprovalRules.label(toolKind!!).lowercase()} here)"))
            return buildJsonObject { putJsonObject("outcome") { put("outcome", "selected"); put("optionId", auto.optionId) } }
        }
        val item = ChatItem.Permission(toolTitle, options, CompletableDeferred(), toolKind)
        add(item)
        onNeedsAttention?.invoke(this, "Approve: $toolTitle")
        val chosen = item.decision.await()
        return buildJsonObject {
            putJsonObject("outcome") {
                if (chosen == null) {
                    put("outcome", "cancelled")
                } else {
                    put("outcome", "selected")
                    put("optionId", chosen)
                }
            }
        }
    }

    private suspend fun readTextFile(params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val file = File(params.string("path") ?: throw AcpException(-32602, "path required"))
        if (!file.isFile) throw AcpException(-32002, "File not found: ${file.path}")
        val line = params["line"]?.jsonPrimitive?.intOrNull
        val limit = params["limit"]?.jsonPrimitive?.intOrNull
        val text = if (line == null && limit == null) {
            // A multi-GB file would take the app down; agents can page with line/limit instead.
            if (file.length() > MAX_READ_BYTES) {
                throw AcpException(-32603, "File too large to read whole (${file.length() / (1024 * 1024)} MB); pass line and limit")
            }
            file.readText()
        } else {
            file.useLines { lines ->
                lines.drop(((line ?: 1) - 1).coerceAtLeast(0))
                    .let { if (limit != null) it.take(limit) else it }
                    .joinToString("\n")
            }
        }
        buildJsonObject { put("content", text) }
    }

    private suspend fun writeTextFile(params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val file = File(params.string("path") ?: throw AcpException(-32602, "path required"))
        file.parentFile?.mkdirs()
        file.writeText(params.string("content") ?: "")
        JsonObject(emptyMap())
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun changed() {
        updatedAt = System.currentTimeMillis()
        onChanged?.invoke(this)
    }

    /**
     * Saves what a running turn has streamed so far, at most every [STREAM_SAVE_MS]. Without it
     * a turn's output existed only in memory until the turn ended, and if Android killed the app
     * mid-turn, resuming dropped the agent's replay of it too (the transcript "already had" it).
     */
    private fun saveSoon() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            kotlinx.coroutines.delay(STREAM_SAVE_MS)
            changed()
        }
    }

    private fun lastAgentText(): String =
        (items.lastOrNull { it is ChatItem.AgentText } as? ChatItem.AgentText)?.text?.trim()?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun failureText(e: Exception): String = (e.message ?: e.toString()) + stderrSuffix()

    private fun stderrSuffix(): String =
        if (stderrTail.isEmpty()) "" else "\n\n" + stderrTail.takeLast(8).joinToString("\n")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val STREAM_SAVE_MS = 2_000L
        private const val TAG = "ChatSession"
        private const val MAX_READ_BYTES = 16L * 1024 * 1024
        private val URL_REGEX = Regex("""https://\S+""")
        private val LOGIN_HINT = Regex("(?i)auth|login|log in|sign in|oauth")
    }
}
