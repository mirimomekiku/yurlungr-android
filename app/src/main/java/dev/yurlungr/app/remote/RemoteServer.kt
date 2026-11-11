package dev.yurlungr.app.remote

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import dev.yurlungr.app.agents.agentStatuses
import android.util.Base64
import dev.yurlungr.app.chat.Attachment
import dev.yurlungr.app.chat.Attachments
import dev.yurlungr.app.chat.ChatItem
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import dev.yurlungr.app.chat.ChatListener
import dev.yurlungr.app.chat.ChatSession
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.chat.ChatStore
import dev.yurlungr.app.remote.RemoteProtocol.long
import dev.yurlungr.app.remote.RemoteProtocol.str
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.runtime.RuntimeService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import org.java_websocket.drafts.Draft_6455
import kotlin.concurrent.thread

/**
 * Lets another phone use this phone's agents: a WebSocket server (see [RemoteProtocol])
 * that streams chats and accepts prompts. Off by default. Binds only to the Tailscale
 * address unless local Wi-Fi is explicitly allowed; every connection must present the
 * pairing code first.
 */
object RemoteServer : ChatListener {
    private const val TAG = "RemoteServer"
    private const val PREFS = "remote_server"
    private const val CLOSE_BAD_CODE = 4001
    /** The host user disconnected this device; the client doesn't auto-reconnect. */
    private const val CLOSE_KICKED = 4002
    /** The client speaks protocol 1 (sends the code in the clear): it needs updating. */
    private const val CLOSE_OLD_VERSION = 4003
    /** A sealed frame failed to open: tampered with, replayed or out of order. */
    private const val CLOSE_TAMPERED = 4004
    private const val CLOSE_POLICY = 1008
    /** Unpaired connections get this long to pair. */
    private const val PAIRING_TIMEOUT_MS = 10_000L
    /** Nothing an unpaired client legitimately sends is bigger than this. */
    private const val MAX_UNPAIRED_CHARS = 4 * 1024
    /** Largest frame an unpaired connection may announce (checked before the library buffers it). */
    internal const val MAX_UNPAIRED_FRAME = 64 * 1024L
    /** Connections still pairing, at most (and one per address): each may buffer a frame. */
    private const val MAX_UNPAIRED = 4

    sealed interface Status {
        data object Off : Status
        data class Running(val addresses: List<String>, val port: Int) : Status
        /** Binding the port; [Running] once it's listening. */
        data object Starting : Status
        data class Failed(val message: String) : Status
    }

    var status by mutableStateOf<Status>(Status.Off)
        private set
    var clientCount by mutableIntStateOf(0)
        private set

    /** A paired device and what it's doing, for the Devices screen. */
    data class ConnectedDevice(val name: String, val address: String, val since: Long, val openChats: Int, val trustedId: String? = null)

    /** Devices that paired and can reconnect with their own secret (see [RemoteTrust]). */
    var trusted by mutableStateOf<List<TrustedDevice>>(emptyList())
        private set
    private val trust by lazy { RemoteTrust(appContext) }

    /**
     * Whether a paired device may start chats that skip permission prompts (or switch a chat to
     * such a mode). Off by default: then only someone holding this phone can turn approvals off.
     */
    var allowRemoteSkip: Boolean
        get() = prefs.getBoolean("allow_remote_skip", false)
        set(value) = prefs.edit { putBoolean("allow_remote_skip", value) }

    var devices by mutableStateOf<List<ConnectedDevice>>(emptyList())
        private set

    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var appContext: Context
    private var server: Server? = null
    /** The previous server, still releasing its port; the next one binds after it's done. */
    private var stopping: Thread? = null

    val isRunning: Boolean get() = server != null

    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        private set(value) = prefs.edit { putBoolean("enabled", value) }

    var allowLan by mutableStateOf(false)
        private set

    /** Pairing code clients must send. ~100 bits, shown grouped for typing. */
    var code by mutableStateOf("")
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        allowLan = prefs.getBoolean("allow_lan", false)
        code = prefs.getString("code", null) ?: newCode().also { c -> prefs.edit { putString("code", c) } }
        trusted = trust.all()
        if (enabled && RuntimePaths(appContext).isBootstrapInstalled) start()
    }

    fun enable(on: Boolean) {
        enabled = on
        if (on) start() else stop()
    }

    fun allowLocalWifi(on: Boolean) {
        allowLan = on
        prefs.edit { putBoolean("allow_lan", on) }
        if (enabled) start()
    }

    /**
     * New code, for pairing new devices. Devices that already paired keep their own secret and
     * stay connected; to lock one out, [revoke] it.
     */
    fun resetCode() {
        code = newCode()
        prefs.edit { putString("code", code) }
        server?.connections?.filter { it.client?.authed != true }?.forEach { it.close(CLOSE_BAD_CODE, "Pairing code changed") }
    }

    /**
     * Forgets a paired device: its secret stops working and it's disconnected. The pairing code
     * changes too, since that device has seen it.
     */
    fun revoke(id: String) {
        trust.revoke(id)
        trusted = trust.all()
        server?.connections?.filter { it.client?.trustedId == id }?.forEach { it.close(CLOSE_BAD_CODE, "This device was removed by the host") }
        resetCode()
    }

    /** Drops one paired device for now (it can reconnect; [revoke] keeps it out). */
    fun disconnect(device: ConnectedDevice) {
        authed().filter { c ->
            runCatching { c.remoteSocketAddress?.address?.hostAddress }.getOrNull().orEmpty() == device.address &&
                c.client?.since == device.since
        }.forEach { it.close(CLOSE_KICKED, "Disconnected by the host") }
    }

    fun start() {
        stop()
        val tailscale = tailscaleAddresses(appContext)
        val bindHost = when {
            // Every interface; connections from outside Tailscale and private LAN ranges
            // (e.g. mobile data, hotspot clients on public addresses) are refused in onOpen.
            allowLan -> "0.0.0.0"
            tailscale.isNotEmpty() -> tailscale.first()
            else -> {
                status = Status.Failed("Tailscale isn't connected on this phone. Open the Tailscale app, or allow local Wi-Fi.")
                return
            }
        }
        val shown = if (allowLan) (tailscale + lanAddresses()).distinct() else tailscale
        val s = Server(InetSocketAddress(bindHost, RemoteProtocol.DEFAULT_PORT)).apply {
            isReuseAddr = true
            connectionLostTimeout = 30
        }
        server = s
        // Running once bound (onStart); until then it's starting.
        status = Status.Starting
        // Rebinding the port while the previous server is still closing fails with "address in use".
        val previous = stopping
        thread(name = "remote-start", isDaemon = true) {
            previous?.join(3_000)
            s.start() // binding errors arrive in onError
        }
        ChatSessions.onRegistryChanged = {
            attachListeners()
            broadcastChats()
        }
        attachListeners()
        RuntimeService.refresh(appContext)
    }

    fun stop() {
        val s = server ?: return
        server = null
        ChatSessions.onRegistryChanged = null
        ChatSessions.chats.forEach { it.listeners -= this }
        stopping = thread(name = "remote-stop", isDaemon = true) { runCatching { s.stop(1_000) } }
        status = Status.Off
        clientCount = 0
        devices = emptyList()
        RuntimeService.refresh(appContext)
    }

    private fun attachListeners() = ChatSessions.chats.forEach { it.listeners += this }

    // ---- outgoing ----------------------------------------------------------------------

    private class Client {
        /** Sent in the challenge; the client's proof must cover it. */
        val hostNonce: ByteArray = RemotePairing.newNonce()
        /** Set (on the main thread) once paired; read by the socket thread to open frames. */
        @Volatile var channel: SecureChannel? = null
        val authed: Boolean get() = channel != null
        var device: String? = null
        /** The [TrustedDevice] it paired as. */
        var trustedId: String? = null
        var since = 0L
        val subscriptions = mutableSetOf<String>()
    }

    private val WebSocket.client: Client? get() = getAttachment<Client>()

    /** Sends to a paired device, sealed with its session keys. */
    private fun send(conn: WebSocket, msg: JsonObject) = sendText(conn, msg.toString())

    private fun sendText(conn: WebSocket, text: String) {
        val channel = conn.client?.channel ?: return
        runCatching { conn.send(channel.seal(text)) }
    }

    private fun refreshClients() {
        val conns = authed()
        val changed = conns.size != clientCount
        clientCount = conns.size
        // The notification says how many devices are connected.
        if (changed) RuntimeService.refresh(appContext)
        devices = conns.map { c ->
            ConnectedDevice(
                name = c.client?.device?.takeIf { it.isNotBlank() } ?: "Unknown device",
                address = runCatching { c.remoteSocketAddress?.address?.hostAddress }.getOrNull().orEmpty(),
                since = c.client?.since ?: 0L,
                openChats = c.client?.subscriptions?.size ?: 0,
                trustedId = c.client?.trustedId,
            )
        }
    }

    private fun authed(): List<WebSocket> = server?.connections.orEmpty().filter { it.client?.authed == true }

    private fun toSubscribers(chatId: String, msg: JsonObject) {
        val text = msg.toString()
        authed().filter { chatId in it.client!!.subscriptions }.forEach { sendText(it, text) }
    }

    private fun broadcastChats() {
        val msg = buildJsonObject {
            put("t", "chats")
            put("chats", JsonArray(ChatSessions.chats.map(RemoteProtocol::summary)))
        }
        authed().forEach { send(it, msg) }
    }

    override fun onItemAdded(chat: ChatSession, item: ChatItem) = sendItem(chat, item)
    override fun onItemUpdated(chat: ChatSession, item: ChatItem) = sendItem(chat, item)

    private fun sendItem(chat: ChatSession, item: ChatItem) = toSubscribers(chat.id, buildJsonObject {
        put("t", "item")
        put("chat", chat.id)
        put("item", ChatStore.encodeItem(item, wire = true))
    })

    override fun onTextAppended(chat: ChatSession, item: ChatItem, delta: String) = toSubscribers(chat.id, buildJsonObject {
        put("t", "append")
        put("chat", chat.id)
        put("k", item.key)
        put("text", delta)
    })

    override fun onStateChanged(chat: ChatSession) {
        toSubscribers(chat.id, RemoteProtocol.stateMessage(chat))
        broadcastChats()
    }

    override fun onMetaChanged(chat: ChatSession) {
        toSubscribers(chat.id, RemoteProtocol.metaMessage(chat))
        broadcastChats()
    }

    // ---- incoming (main thread) ------------------------------------------------------------

    /** The unpaired client's answer to our challenge (text frame). */
    private fun handleHello(conn: WebSocket, text: String) {
        val client = conn.client ?: return
        if (client.authed || !conn.isOpen) return
        val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (msg?.str("t") != "hello") return reject(conn, CLOSE_POLICY, "Pair first")
        if (msg.str("token") != null || (msg.long("v") ?: 1L) < RemoteProtocol.VERSION) {
            return reject(conn, CLOSE_OLD_VERSION, "Update Yurlungr on this phone to connect to this host.")
        }
        val clientNonce = RemotePairing.fromHex(msg.str("nonce"), RemotePairing.NONCE_BYTES)
            ?: return reject(conn, CLOSE_POLICY, "Bad hello")
        val known = msg.str("id")?.let(trust::find)
        if (msg.str("id") != null && known == null) return reject(conn, CLOSE_BAD_CODE, "This device was removed by the host")
        // A trusted device's secret stands in for the code, in the same handshake.
        val pairingCode = known?.secret ?: code
        val expected = RemotePairing.clientProof(pairingCode, client.hostNonce, clientNonce)
        if (!RemotePairing.matches(expected, RemotePairing.fromHex(msg.str("proof"), expected.size))) {
            return reject(conn, CLOSE_BAD_CODE, if (known != null) "This device was removed by the host" else "Wrong pairing code")
        }
        // Prove we know the code too, in the clear, before anything sealed follows.
        runCatching {
            conn.send(buildJsonObject {
                put("t", "auth")
                put("proof", RemotePairing.toHex(RemotePairing.hostProof(pairingCode, client.hostNonce, clientNonce)))
            }.toString())
        }
        client.channel = RemotePairing.channel(pairingCode, client.hostNonce, clientNonce, isHost = true)
        client.device = msg.str("device")?.take(80)
        client.since = System.currentTimeMillis()
        val device = known ?: trust.add(client.device.orEmpty())
        client.trustedId = device.id
        if (known != null) trust.touch(known.id, client.device) else {
            // Someone paired with the code: tell whoever holds this phone.
            dev.yurlungr.app.runtime.TaskNotifications.notify(appContext, "New device paired: ${device.name}\nIt can use this phone's agents. Remove it under Devices if that wasn't you.")
        }
        trusted = trust.all()
        send(conn, buildJsonObject {
            put("t", "welcome")
            put("version", RemoteProtocol.VERSION)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("agents", JsonArray(RemoteProtocol.agents(agentStatuses(RuntimePaths(appContext)))))
            put("chats", JsonArray(ChatSessions.chats.map(RemoteProtocol::summary)))
        })
        // Its own secret, sealed: from now on it pairs with this instead of the code.
        if (known == null) send(conn, buildJsonObject {
            put("t", "trust")
            put("id", device.id)
            put("secret", device.secret)
        })
        refreshClients()
        Log.i(TAG, "Paired: ${client.device} from ${conn.remoteSocketAddress}")
    }

    private fun reject(conn: WebSocket, code: Int, reason: String) {
        Log.w(TAG, "Rejected ${conn.remoteSocketAddress}: $reason")
        conn.close(code, reason)
    }

    /** A message from a paired device (already opened from its sealed frame). */
    private fun handle(conn: WebSocket, text: String) {
        val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val client = conn.client ?: return
        if (!client.authed) return
        val type = msg.str("t")

        val chat = ChatSessions.get(msg.str("chat"))
        when (type) {
            "open" -> chat?.let {
                client.subscriptions += it.id
                refreshClients()
                send(conn, RemoteProtocol.snapshot(it))
            }
            "close" -> msg.str("chat")?.let { client.subscriptions -= it }.also { refreshClients() }
            "create" -> {
                val agent = RemoteProtocol.agentOf(msg.str("agent")) ?: return
                val folder = msg.str("folder").orEmpty().trim().takeIf(RemoteProtocol::isValidFolder) ?: "scratch"
                val wantsSkip = (msg["skipPermissions"] as? JsonPrimitive)?.booleanOrNull ?: false
                val created = ChatSessions.create(appContext, agent, File(RuntimePaths(appContext).projects, folder), skipPermissions = wantsSkip && allowRemoteSkip)
                client.subscriptions += created.id
                refreshClients()
                send(conn, buildJsonObject {
                    put("t", "created")
                    msg.long("req")?.let { put("req", it) }
                    put("chat", created.id)
                })
            }
            "send" -> chat?.send(msg.str("text").orEmpty(), decodeAttachments(msg["attachments"]))
            "config" -> {
                val id = msg.str("id") ?: return
                val value = msg["value"] as? JsonPrimitive ?: return
                if (value.isString && !allowRemoteSkip && dev.yurlungr.app.chat.PermissionModes.isSkipMode(value.content)) return
                if (value.isString) chat?.setConfigOption(id, value.content)
                else value.booleanOrNull?.let { chat?.setConfigOption(id, it) }
            }
            "cancel" -> chat?.cancel()
            "permission" -> {
                val key = msg.long("k") ?: return
                val option = msg.str("option") ?: return
                chat?.answerPermission(key, option)
            }
            "mode" -> msg.str("mode")?.takeIf { allowRemoteSkip || !dev.yurlungr.app.chat.PermissionModes.isSkipMode(it) }?.let { chat?.setMode(it) }
            "resume" -> chat?.resume()
            "restart" -> chat?.restart()
            "stop" -> chat?.stop()
            "delete" -> chat?.let { ChatSessions.delete(appContext, it) }
        }
    }

    private class Server(address: InetSocketAddress) :
        WebSocketServer(address, listOf(Draft_6455(emptyList(), RemoteProtocol.MAX_FRAME_BYTES))) {
        init {
            // Paired devices may send big frames (attachments); unpaired ones only a small hello.
            // The library allocates a frame's buffer as soon as its header arrives, so the size is
            // checked on the raw bytes first (UnpairedFrameGuard).
            setWebSocketFactory(object : org.java_websocket.server.DefaultWebSocketServerFactory() {
                override fun createWebSocket(a: org.java_websocket.WebSocketAdapter, d: org.java_websocket.drafts.Draft) = Guarded(a, listOf(d))
                override fun createWebSocket(a: org.java_websocket.WebSocketAdapter, d: List<org.java_websocket.drafts.Draft>) = Guarded(a, d)
            })
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val remote = conn.remoteSocketAddress?.address?.hostAddress
            if (!RemoteProtocol.isAllowedPeer(remote, allowLan)) {
                Log.w(TAG, "Refused connection from $remote (not Tailscale or local Wi-Fi)")
                conn.close(CLOSE_POLICY, "Not allowed from this network")
                return
            }
            val pending = connections.filter { it !== conn && it.getAttachment<Client>()?.authed != true }
            if (pending.size >= MAX_UNPAIRED || pending.any { it.remoteSocketAddress?.address?.hostAddress == remote }) {
                Log.w(TAG, "Refused connection from $remote (too many pairing at once)")
                conn.close(CLOSE_POLICY, "Busy: try again in a moment")
                return
            }
            val client = Client()
            conn.setAttachment(client)
            runCatching {
                conn.send(buildJsonObject {
                    put("t", "challenge")
                    put("v", RemoteProtocol.VERSION)
                    put("nonce", RemotePairing.toHex(client.hostNonce))
                }.toString())
            }
            main.postDelayed({ if (!client.authed && conn.isOpen) reject(conn, CLOSE_POLICY, "Pairing timed out") }, PAIRING_TIMEOUT_MS)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            main.post { refreshClients() }
        }

        /** Text frames are only for pairing; once paired everything is sealed (binary). */
        override fun onMessage(conn: WebSocket, message: String) {
            val client = conn.client ?: return
            if (client.authed || message.length > MAX_UNPAIRED_CHARS) {
                conn.close(CLOSE_POLICY, "Unexpected message")
                return
            }
            main.post { handleHello(conn, message) }
        }

        /** Opened here, on this connection's socket thread, so frames are opened in order. */
        override fun onMessage(conn: WebSocket, message: ByteBuffer) {
            val channel = conn.client?.channel ?: run {
                conn.close(CLOSE_POLICY, "Pair first")
                return
            }
            val bytes = ByteArray(message.remaining()).also { message.get(it) }
            val text = try {
                channel.open(bytes)
            } catch (e: GeneralSecurityException) {
                Log.w(TAG, "Dropping ${conn.remoteSocketAddress}: frame failed authentication")
                conn.close(CLOSE_TAMPERED, "Message failed authentication")
                return
            }
            main.post { handle(conn, text) }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.w(TAG, "WebSocket error", ex)
            if (conn == null) {
                // Server-level failure (e.g. port in use): report it.
                main.post {
                    if (server === this) {
                        server = null
                        status = Status.Failed("Couldn't start sharing: ${ex.message}")
                        RuntimeService.refresh(appContext)
                    }
                }
            }
        }

        override fun onStart() {
            main.post {
                if (server === this) {
                    val shown = if (allowLan) (tailscaleAddresses(appContext) + lanAddresses()).distinct() else tailscaleAddresses(appContext)
                    status = Status.Running(shown, RemoteProtocol.DEFAULT_PORT)
                    RuntimeService.refresh(appContext)
                }
            }
        }
    }

    /** A connection that refuses oversized frames until it's paired. */
    private class Guarded(adapter: org.java_websocket.WebSocketAdapter, drafts: List<org.java_websocket.drafts.Draft>) :
        org.java_websocket.WebSocketImpl(adapter, drafts) {
        private val guard = UnpairedFrameGuard(MAX_UNPAIRED_FRAME)

        override fun decode(socketBuffer: ByteBuffer) {
            val unpaired = getAttachment<Client>()?.authed != true
            val frames: ByteBuffer? = when {
                !unpaired -> null
                readyState == org.java_websocket.enums.ReadyState.OPEN -> socketBuffer.duplicate()
                // Still the HTTP handshake: frames sent in the same packet start after its blank line.
                else -> socketBuffer.duplicate().also { b -> afterHeaders(b)?.let { b.position(it) } ?: b.position(b.limit()) }
            }
            if (frames != null && !guard.accept(frames)) {
                Log.w(TAG, "Dropping $remoteSocketAddress: oversized frame before pairing")
                close(CLOSE_POLICY, "Pair first")
                socketBuffer.position(socketBuffer.limit())
                return
            }
            super.decode(socketBuffer)
        }

        /** Where the bytes after an HTTP header block (ending in a blank line) begin, or null. */
        private fun afterHeaders(b: ByteBuffer): Int? {
            for (i in b.position() until b.limit() - 3) {
                if (b.get(i) == '\r'.code.toByte() && b.get(i + 1) == '\n'.code.toByte() && b.get(i + 2) == '\r'.code.toByte() && b.get(i + 3) == '\n'.code.toByte()) return i + 4
            }
            return null
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun decodeAttachments(el: kotlinx.serialization.json.JsonElement?): List<Attachment> =
        (el as? JsonArray).orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val bytes = runCatching { Base64.decode(o.str("data").orEmpty(), Base64.NO_WRAP) }.getOrNull()
                ?: return@mapNotNull null
            if (bytes.isEmpty() || bytes.size > Attachments.MAX_BYTES) return@mapNotNull null
            Attachment(o.str("name") ?: "file", o.str("mime") ?: "application/octet-stream", bytes)
        }

    private fun newCode(): String {
        // Crockford-style alphabet: no 0/O or 1/I/L confusion when typing it on another phone.
        val alphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        val random = SecureRandom()
        return (1..20).map { alphabet[random.nextInt(alphabet.length)] }.chunked(4).joinToString("-") { it.joinToString("") }
    }

    /**
     * This phone's Tailscale addresses: 100.64.0.0/10 on a VPN interface. Mobile carriers use
     * the same range for carrier-grade NAT on the cellular interface, which must not be chosen.
     */
    fun tailscaleAddresses(context: Context): List<String> {
        val vpn = vpnInterfaces(context)
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && (it.name in vpn || it.name.startsWith("tun")) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .filter { addr -> addr.split('.').map { it.toInt() }.let { o -> o[0] == 100 && o[1] in 64..127 } }
        }.getOrDefault(emptyList())
    }

    /** Interface names of the VPNs Android knows about (Tailscale is one). */
    private fun vpnInterfaces(context: Context): Set<String> = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.filter { n -> cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
            .mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
    }.getOrDefault(emptySet())

    private fun lanAddresses(): List<String> = ipv4Addresses().filter { addr ->
        val o = addr.split('.').map { it.toInt() }
        o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }

    private fun ipv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
}
