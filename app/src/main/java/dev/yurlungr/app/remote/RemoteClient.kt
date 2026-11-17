package dev.yurlungr.app.remote

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.chat.ChatState
import dev.yurlungr.app.remote.RemoteProtocol.bool
import dev.yurlungr.app.remote.RemoteProtocol.long
import dev.yurlungr.app.remote.RemoteProtocol.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.UUID

/** A phone this one can connect to. [address] is a Tailscale IP / MagicDNS name, optionally with :port. */
data class RemoteHost(
    val id: String, val name: String, val address: String, val code: String,
    /** This phone's own id and secret on that host, given after the first pairing (see RemoteTrust). */
    val deviceId: String? = null, val secret: String? = null,
)

data class RemoteChatSummary(val id: String, val agent: Agent, val title: String, val folder: String, val state: ChatState, val updatedAt: Long)

data class RemoteAgentStatus(val agent: Agent, val installed: Boolean, val signedIn: Boolean?)

/** Saved hosts (app-private prefs; codes and secrets sealed by [SecretBox]). */
object RemoteHosts {
    private const val PREFS = "remote_hosts"
    val hosts = mutableStateListOf<RemoteHost>()
    private lateinit var appContext: Context
    private val box get() = SecretBox.app

    fun init(context: Context) {
        appContext = context.applicationContext
        hosts.clear()
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("hosts", null) ?: return
        val (list, migrate) = decode(raw, box)
        hosts += list
        if (migrate) save()
    }

    /** Hosts in [raw], with codes and secrets opened; true if a plaintext one should be re-saved sealed. */
    internal fun decode(raw: String, box: SecretBox): Pair<List<RemoteHost>, Boolean> {
        var migrate = false
        fun open(stored: String?): String? = box.read(stored).also { if (it.rewrite != null) migrate = true }.value
        val list = runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                // A sealed value that can't be opened (keystore key lost): no secret means pair
                // again with the code; no code means the user re-enters it.
                RemoteHost(o.str("id") ?: return@mapNotNull null, o.str("name").orEmpty(), o.str("address").orEmpty(), open(o.str("code")).orEmpty(), o.str("deviceId"), open(o.str("secret")))
            }
        }.getOrDefault(emptyList())
        return list to migrate
    }

    internal fun encode(list: List<RemoteHost>, box: SecretBox): String = JsonArray(list.map { h ->
        buildJsonObject {
            put("id", h.id); put("name", h.name); put("address", h.address); put("code", box.seal(h.code))
            h.deviceId?.let { put("deviceId", it) }
            h.secret?.let { put("secret", box.seal(it)) }
        }
    }).toString()

    fun add(name: String, address: String, code: String): RemoteHost {
        val host = RemoteHost(UUID.randomUUID().toString(), name.trim().ifEmpty { address.trim() }, address.trim(), code.trim())
        hosts += host
        save()
        return host
    }

    fun remove(host: RemoteHost) {
        RemoteConnections.drop(host.id)
        hosts.remove(host)
        save()
    }

    fun get(id: String) = hosts.firstOrNull { it.id == id }

    /** Pairs again from scratch: forgets this phone's secret on [hostId] and saves a new pairing code. */
    fun repair(hostId: String, code: String) {
        val i = hosts.indexOfFirst { it.id == hostId }.takeIf { it >= 0 } ?: return
        hosts[i] = hosts[i].copy(code = code.trim(), deviceId = null, secret = null)
        save()
    }

    /** Saves (or clears) the id and secret the host gave this phone. */
    fun setTrust(hostId: String, deviceId: String?, secret: String?) {
        val i = hosts.indexOfFirst { it.id == hostId }.takeIf { it >= 0 } ?: return
        hosts[i] = hosts[i].copy(deviceId = deviceId, secret = secret)
        save()
    }

    private fun save() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("hosts", encode(hosts.toList(), box)) }
    }
}

/** One live connection per host, kept while the app runs (reconnects automatically). */
object RemoteConnections {
    private val connections = HashMap<String, RemoteConnection>()

    fun get(host: RemoteHost): RemoteConnection =
        connections.getOrPut(host.id) { RemoteConnection(host) }.also { it.connect() }

    fun drop(hostId: String) {
        connections.remove(hostId)?.disconnect()
    }
}

class RemoteConnection(val host: RemoteHost) {
    sealed interface Status {
        data object Connecting : Status
        data object Connected : Status
        /** [refused]: the host turned this phone away; the user may want to [pairAgain]. */
        data class Failed(val message: String, val retrying: Boolean, val refused: Boolean = false) : Status
    }

    var status by mutableStateOf<Status>(Status.Connecting)
        private set
    var deviceName by mutableStateOf(host.name)
        private set
    val chats = mutableStateListOf<RemoteChatSummary>()
    var agents by mutableStateOf<List<RemoteAgentStatus>>(emptyList())
        private set

    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    private val openChats = HashMap<String, RemoteChat>()
    private val pendingCreates = HashMap<Long, (RemoteChat) -> Unit>()
    private var nextReq = 1L
    @Volatile private var socket: WebSocketClient? = null
    /** The current socket's sealed channel, once the host proved it knows the code. */
    @Volatile private var channel: SecureChannel? = null
    /** This phone's id and secret on the host, once it has them; the code until then. */
    @Volatile private var deviceId: String? = host.deviceId
    @Volatile private var secret: String? = host.secret
    @Volatile private var code: String = host.code
    /** What this handshake proves: the secret when there is one, else the pairing code. */
    private val key: String get() = secret ?: code
    private var wanted = false
    private var retryDelayMs = 2_000L
    /** The host turned this phone away since it last connected. */
    private var refused = false

    fun connect() {
        wanted = true
        if (socket != null) return
        status = Status.Connecting
        channel = null
        val uri = runCatching { URI("ws://${withPort(host.address)}/") }.getOrElse {
            status = Status.Failed("Invalid address: ${host.address}", retrying = false)
            return
        }
        // A paired phone names itself up front so the host gives it a slot apart from unknown devices.
        val headers = deviceId?.takeIf { secret != null }?.let { mapOf(RemoteProtocol.DEVICE_HEADER to it) }.orEmpty()
        socket = object : WebSocketClient(uri, Draft_6455(emptyList(), RemoteProtocol.MAX_FRAME_BYTES), headers) {
            // Pairing runs on this socket's thread, so the channel exists before any sealed frame is read.
            private var hostNonce: ByteArray? = null
            private var clientNonce: ByteArray? = null

            override fun onOpen(handshake: ServerHandshake) {
                // The host speaks first (challenge). A host that stays silent runs an older version.
                main.postDelayed({
                    if (this@RemoteConnection.socket === this && this@RemoteConnection.channel == null && isOpen) close(CLOSE_NO_CHALLENGE, "")
                }, HANDSHAKE_TIMEOUT_MS)
            }

            override fun onMessage(message: String) {
                if (this@RemoteConnection.socket !== this) return
                val msg = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return
                when (msg.str("t")) {
                    "challenge" -> {
                        val nonce = RemotePairing.fromHex(msg.str("nonce"), RemotePairing.NONCE_BYTES)
                        if (nonce == null || hostNonce != null) return close(CLOSE_POLICY, "Bad challenge")
                        val mine = RemotePairing.newNonce()
                        hostNonce = nonce
                        clientNonce = mine
                        send(buildJsonObject {
                            put("t", "hello")
                            put("v", RemoteProtocol.VERSION)
                            put("nonce", RemotePairing.toHex(mine))
                            if (secret != null) deviceId?.let { put("id", it) }
                            put("proof", RemotePairing.toHex(RemotePairing.clientProof(key, nonce, mine)))
                            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        }.toString())
                    }
                    "auth" -> {
                        val hn = hostNonce ?: return close(CLOSE_POLICY, "Unexpected auth")
                        val cn = clientNonce ?: return close(CLOSE_POLICY, "Unexpected auth")
                        val expected = RemotePairing.hostProof(key, hn, cn)
                        if (this@RemoteConnection.channel != null || !RemotePairing.matches(expected, RemotePairing.fromHex(msg.str("proof"), expected.size))) {
                            return close(CLOSE_BAD_HOST, "")
                        }
                        this@RemoteConnection.channel = RemotePairing.channel(key, hn, cn, isHost = false)
                    }
                    // Anything else in the clear is ignored: once paired, the host only sends sealed frames.
                }
            }

            override fun onMessage(bytes: ByteBuffer) {
                if (this@RemoteConnection.socket !== this) return
                val ch = this@RemoteConnection.channel ?: return close(CLOSE_POLICY, "Not paired")
                val text = try {
                    ch.open(ByteArray(bytes.remaining()).also { bytes.get(it) })
                } catch (e: GeneralSecurityException) {
                    return close(CLOSE_TAMPERED, "")
                }
                val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                main.post { handle(msg) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                // Read on this thread, before onDisconnected clears it: did the host prove itself on this connection?
                val proven = this@RemoteConnection.socket === this && this@RemoteConnection.channel != null
                main.post { onDisconnected(this, code, reason, proven) }
            }

            override fun onError(ex: Exception) {
                Log.w(TAG, "connection error", ex)
            }
        }.apply {
            connectionLostTimeout = 30
            connect()
        }
    }

    fun disconnect() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        socket?.close()
        socket = null
        channel = null
    }

    /** Forgets this phone's secret on the host and pairs again with [newCode]: the user's call, never automatic. */
    fun pairAgain(newCode: String) {
        disconnect()
        RemoteHosts.repair(host.id, newCode)
        code = newCode.trim()
        deviceId = null
        secret = null
        retryDelayMs = 2_000L
        refused = false
        connect()
    }

    /**
     * [proven]: the host proved it holds the code or this phone's secret on this connection. Until
     * then anyone answering at the host's address can send these close codes, so they only count
     * as a failed attempt: nothing is forgotten and reconnecting goes on.
     */
    private fun onDisconnected(which: WebSocketClient, code: Int, reason: String?, proven: Boolean) {
        if (socket !== which) return
        socket = null
        channel = null
        if (code == CLOSE_BAD_HOST) {
            wanted = false
            status = Status.Failed(
                "This address answered but couldn't prove it has the pairing code. Check the address and code; on shared Wi-Fi, someone may be impersonating the host.",
                retrying = false,
            )
            return
        }
        if (code == CLOSE_OLD_VERSION) {
            // The host sends this before its proof, so it's never authoritative: keep retrying.
            status = Status.Failed("The host asks for a newer Yurlungr on this phone. Update it to connect.", retrying = wanted)
            if (wanted) scheduleRetry()
            return
        }
        if (code == CLOSE_NO_CHALLENGE) {
            status = Status.Failed("The host didn't start pairing. It may run an older Yurlungr: update both phones.", retrying = wanted)
            if (wanted) scheduleRetry()
            return
        }
        if (code == CLOSE_BAD_CODE) refused = true
        if (code == CLOSE_BAD_CODE && proven) {
            // Removed on the host while connected. The secret stays until the user pairs again.
            wanted = false
            status = Status.Failed("The host removed this phone. Pair again with the code shown on the host.", retrying = false, refused = true)
            return
        }
        if (code == CLOSE_BAD_CODE) {
            // A host that forgot this phone can't prove itself to it, so this may be the host or an
            // impersonator: keep retrying and leave forgetting the secret to the user.
            val message = if (secret != null) "The host refused this phone. If you removed it there, pair again."
            else "The host rejected the pairing code. Check the code shown on the host, then pair again."
            status = Status.Failed(message, retrying = wanted, refused = true)
            if (wanted) scheduleRetry()
            return
        }
        if (code == CLOSE_KICKED && proven) {
            wanted = false
            status = Status.Failed("The host disconnected this phone.", retrying = false)
            return
        }
        val detail = reason?.takeIf { it.isNotBlank() } ?: "Can't reach ${host.address}. Is Tailscale on and sharing enabled on the host?"
        // A refusal stays shown (with Pair again) while retries fail for other reasons, e.g. the host's backoff.
        status = Status.Failed(detail, retrying = wanted, refused = refused)
        if (wanted) scheduleRetry()
    }

    private fun scheduleRetry() {
        main.postDelayed({ if (wanted) connect() }, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000)
    }

    /** Sealed for the host; dropped until paired. */
    private fun send(msg: JsonObject) {
        val s = socket?.takeIf { it.isOpen } ?: return
        val ch = channel ?: return
        runCatching { s.send(ch.seal(msg.toString())) }
    }

    /** The mirrored chat, subscribed to updates. */
    fun chat(id: String): RemoteChat? {
        openChats[id]?.let { return it }
        val summary = chats.firstOrNull { it.id == id } ?: return null
        return newChat(id, summary.agent).also { it.open() }
    }

    fun createChat(agent: Agent, folder: String, skipPermissions: Boolean = false, onCreated: (RemoteChat) -> Unit) {
        val req = nextReq++
        pendingCreates[req] = onCreated
        send(buildJsonObject {
            put("t", "create")
            put("req", req)
            put("agent", agent.name)
            put("folder", folder)
            put("skipPermissions", skipPermissions)
        })
    }

    private fun newChat(id: String, agent: Agent) = RemoteChat(id, agent, send = ::send, onDeleted = { chat ->
        openChats.remove(chat.id)
        chats.removeAll { it.id == chat.id }
    }).also { openChats[id] = it }

    private fun handle(msg: JsonObject) {
        if (msg.str("t") == "trust") {
            // Our own secret from now on: the host can then remove this phone alone, and a new code doesn't affect it.
            val id = msg.str("id") ?: return
            val s = msg.str("secret")?.takeIf { it.length == RemoteTrust.SECRET_BYTES * 2 } ?: return
            deviceId = id
            secret = s
            RemoteHosts.setTrust(host.id, id, s)
            return
        }
        when (msg.str("t")) {
            "welcome" -> {
                status = Status.Connected
                retryDelayMs = 2_000L
                refused = false
                msg.str("device")?.let { deviceName = it }
                agents = (msg["agents"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    RemoteAgentStatus(RemoteProtocol.agentOf(o.str("agent")) ?: return@mapNotNull null, o.bool("installed"), (o["signedIn"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull)
                }
                readChats(msg)
                openChats.values.forEach { it.open() } // resubscribe after a reconnect
            }
            "chats" -> readChats(msg)
            "created" -> {
                val id = msg.str("chat") ?: return
                val callback = msg.long("req")?.let { pendingCreates.remove(it) }
                val agent = chats.firstOrNull { it.id == id }?.agent ?: Agent.Claude
                val chat = openChats[id] ?: newChat(id, agent)
                chat.open()
                callback?.invoke(chat)
            }
            else -> msg.str("chat")?.let { openChats[it] }?.apply(msg)
        }
    }

    private fun readChats(msg: JsonObject) {
        val list = (msg["chats"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            RemoteChatSummary(
                id = o.str("id") ?: return@mapNotNull null,
                agent = RemoteProtocol.agentOf(o.str("agent")) ?: return@mapNotNull null,
                title = o.str("title").orEmpty(),
                folder = o.str("folder").orEmpty(),
                state = RemoteProtocol.decodeState(o.str("state"), null),
                updatedAt = o.long("updatedAt") ?: 0L,
            )
        }
        chats.clear()
        chats.addAll(list)
    }

    companion object {
        private const val TAG = "RemoteConnection"
        private const val CLOSE_BAD_CODE = 4001
        private const val CLOSE_KICKED = 4002
        private const val CLOSE_OLD_VERSION = 4003
        private const val CLOSE_TAMPERED = 4004
        /** Local: the host failed its proof. */
        private const val CLOSE_BAD_HOST = 4005
        /** Local: no challenge arrived in time. */
        private const val CLOSE_NO_CHALLENGE = 4006
        private const val CLOSE_POLICY = 1008
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L

        fun withPort(address: String): String {
            val a = address.trim().removePrefix("ws://").trimEnd('/')
            return if (a.substringAfterLast(']').contains(':')) a else "$a:${RemoteProtocol.DEFAULT_PORT}"
        }
    }
}
