package dev.yurlungr.app.remote

import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.agents.AgentStatus
import dev.yurlungr.app.chat.AcpExtras
import dev.yurlungr.app.chat.ChatSession
import dev.yurlungr.app.chat.ChatState
import dev.yurlungr.app.chat.ChatStore
import dev.yurlungr.app.chat.SessionMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Messages between a host phone (runs the agents) and a client phone (shows them), as JSON
 * over a WebSocket. Every message has a type field "t".
 *
 * Pairing (text frames, see [RemotePairing]): host challenge{v,nonce} → client hello{v,nonce,proof,device}
 * → host auth{proof}. After that every message is a binary frame sealed by [SecureChannel]:
 *
 * client → host: open{chat} · close{chat} · create{req,agent,folder,skipPermissions}
 *                send{chat,text} · cancel · permission{chat,k,option} · mode{chat,mode}
 *                resume · restart · stop · delete (all {chat})
 * host → client: welcome{device,agents,chats} · chats{chats} · created{req,chat}
 *                snapshot{chat} · item{chat,item} · append{chat,k,text} · state{chat,…} · meta{chat,…}
 *                error{message}
 *
 * Version 1 sent the pairing code itself in hello{token}; hosts now refuse it.
 */
object RemoteProtocol {
    const val VERSION = 2
    const val DEFAULT_PORT = 8765
    /** Largest frame either side accepts: a message with a few 10 MB attachments, base64-encoded. */
    const val MAX_FRAME_BYTES = 64 * 1024 * 1024

    private val FOLDER = Regex("[A-Za-z0-9._-]{1,64}")

    /** A folder name under ~/projects: no separators, and not "." or ".." (which would leave it). */
    fun isValidFolder(name: String): Boolean = FOLDER.matches(name) && name.trim('.').isNotEmpty()

    /**
     * Handshake header a paired client sends with its device id (the same id its hello carries in
     * the clear), so the host can give it a pairing slot apart from unknown devices.
     */
    const val DEVICE_HEADER = "X-Yurlungr-Device"

    /** An IPv4 subnet: [network] as a 32-bit int, [prefix] bits long. */
    data class Subnet(val network: Int, val prefix: Int) {
        private val mask: Int get() = if (prefix <= 0) 0 else (-1 shl (32 - prefix.coerceAtMost(32)))
        operator fun contains(ip: Int): Boolean = (ip and mask) == (network and mask)

        companion object {
            /** The subnet [address]/[prefix] is on, or null for anything but a dotted IPv4 address. */
            fun of(address: String, prefix: Int): Subnet? {
                val ip = ipv4(address) ?: return null
                if (prefix !in 0..32) return null
                return Subnet(ip, prefix)
            }
        }
    }

    /** A dotted IPv4 address as a 32-bit int, or null. */
    fun ipv4(address: String?): Int? {
        val parts = address?.split('.')?.takeIf { it.size == 4 } ?: return null
        var ip = 0
        for (p in parts) {
            val o = p.toIntOrNull()?.takeIf { it in 0..255 && p.length in 1..3 } ?: return null
            ip = (ip shl 8) or o
        }
        return ip
    }

    private val TAILSCALE = Subnet(ipv4("100.64.0.0")!!, 10)
    private val PRIVATE = listOf(Subnet(ipv4("10.0.0.0")!!, 8), Subnet(ipv4("172.16.0.0")!!, 12), Subnet(ipv4("192.168.0.0")!!, 16))

    /**
     * Tailscale's 100.64.0.0/10 when the connection arrived on this phone's Tailscale address
     * ([viaTailscale]): carriers use the same range for carrier-grade NAT, and with local Wi-Fi
     * allowed the server listens on every interface, cellular included. Otherwise only a private
     * address on one of [localSubnets] (this phone's own Wi-Fi / hotspot / Ethernet subnets, see
     * [lanSubnets]; empty unless local Wi-Fi is allowed). Not a whole private range: a carrier or
     * VPN that hands out 10.x addresses must not reach the server.
     */
    fun isAllowedPeer(address: String?, localSubnets: List<Subnet>, viaTailscale: Boolean): Boolean {
        val ip = ipv4(address) ?: return false
        if (ip in TAILSCALE) return viaTailscale
        return PRIVATE.any { ip in it } && localSubnets.any { ip in it }
    }

    /**
     * Whether a connection naming device [claimed] (handshake header [DEVICE_HEADER]) gets one of
     * the slots kept for trusted devices: it must be a [trusted] id, not in backoff after failed
     * or timed-out attempts ([blocked]), and not already pending on another connection ([pending]),
     * so replaying a known id from several addresses can't fill those slots. Otherwise it pairs
     * in the ordinary slots.
     */
    fun getsTrustedSlot(claimed: String?, trusted: Collection<String>, pending: Collection<String?>, blocked: Boolean): Boolean =
        !claimed.isNullOrEmpty() && claimed in trusted && claimed !in pending && !blocked

    /** An interface name that looks cellular, VPN or virtual: never a local Wi-Fi subnet. */
    private val NOT_LAN = Regex("^(rmnet|ccmni|ccemni|pdp|wwan|ppp|tun|tap|ipsec|wg|clat|v4-|dummy|lo|ifb|sit|ip6|gre|r_rmnet|seth|epdg|qmi|usb_rmnet).*", RegexOption.IGNORE_CASE)

    /**
     * The subnets local Wi-Fi peers may come from: each private IPv4 address on a non-cellular,
     * non-VPN interface with its prefix length. Prefixes shorter than /16 are narrowed to /16 so
     * one odd interface can't open a whole /8.
     */
    fun lanSubnets(interfaces: List<Pair<String, List<Pair<String, Int>>>>): List<Subnet> =
        interfaces.filterNot { (name, _) -> NOT_LAN.matches(name) }.flatMap { (_, addrs) ->
            addrs.mapNotNull { (addr, prefix) ->
                val ip = ipv4(addr) ?: return@mapNotNull null
                if (PRIVATE.none { ip in it }) return@mapNotNull null
                Subnet.of(addr, prefix.coerceAtLeast(16))
            }
        }.distinct()

    /**
     * A boolean config option that looks like it turns permission prompts or safety off
     * (auto-approve, skip/bypass permissions, YOLO, dangerous or unsandboxed modes). Without
     * allow-remote-skip, a paired device may not change these either way: some agents phrase
     * them as "require approval", where false is the risky value.
     */
    fun isRiskyConfigToggle(id: String, name: String?): Boolean {
        val text = "$id ${name.orEmpty()}".lowercase()
        return RISKY_TOGGLE.containsMatchIn(text)
    }

    private val RISKY_TOGGLE = Regex("auto[-_ ]?approv|approv|permission|skip|bypass|yolo|danger|unsafe|sandbox|confirm|full[-_ ]?access|trust|allow[-_ ]?all")

    fun encodeState(state: ChatState): Pair<String, String?> = when (state) {
        ChatState.Saved -> "saved" to null
        ChatState.Starting -> "starting" to null
        ChatState.Ready -> "ready" to null
        ChatState.Running -> "running" to null
        ChatState.AuthRequired -> "auth" to null
        is ChatState.Failed -> "failed" to state.message
    }

    fun decodeState(name: String?, message: String?): ChatState = when (name) {
        "starting" -> ChatState.Starting
        "ready" -> ChatState.Ready
        "running" -> ChatState.Running
        "auth" -> ChatState.AuthRequired
        "failed" -> ChatState.Failed(message ?: "Stopped")
        else -> ChatState.Saved
    }

    fun summary(chat: ChatSession) = buildJsonObject {
        put("id", chat.id)
        put("agent", chat.agent.name)
        put("title", chat.title)
        put("folder", chat.folderName)
        put("updatedAt", chat.updatedAt)
        val (state, _) = encodeState(chat.state)
        put("state", state)
    }

    fun agents(statuses: List<AgentStatus>) = statuses.map { s ->
        buildJsonObject {
            put("agent", s.agent.name)
            put("installed", s.installed)
            s.signedIn?.let { put("signedIn", it) }
        }
    }

    fun stateMessage(chat: ChatSession) = buildJsonObject {
        put("t", "state")
        put("chat", chat.id)
        val (state, message) = encodeState(chat.state)
        put("state", state)
        message?.let { put("message", it) }
    }

    fun metaMessage(chat: ChatSession) = buildJsonObject {
        put("t", "meta")
        put("chat", chat.id)
        put("title", chat.title)
        put("skipPermissions", chat.skipPermissions)
        put("cwd", chat.cwdLabel)
        chat.currentModeId?.let { put("mode", it) }
        chat.loginUrl?.let { put("loginUrl", it) }
        put("commands", AcpExtras.encodeCommands(chat.commands))
        put("configOptions", AcpExtras.encodeConfigOptions(chat.configOptions))
        chat.usage?.let { put("usage", AcpExtras.encodeUsage(it)) }
        put("caps", buildJsonObject {
            put("image", chat.caps.image)
            put("embeddedContext", chat.caps.embeddedContext)
        })
        putJsonArray("modes") {
            chat.modes.forEach { m ->
                addJsonObject {
                    put("id", m.id)
                    put("name", m.name)
                    m.description?.let { put("description", it) }
                }
            }
        }
    }

    fun snapshot(chat: ChatSession) = buildJsonObject {
        put("t", "snapshot")
        put("chat", buildJsonObject {
            put("id", chat.id)
            put("agent", chat.agent.name)
            put("folder", chat.folderName)
            metaMessage(chat).forEach { (k, v) -> if (k != "t" && k != "chat") put(k, v) }
            stateMessage(chat).forEach { (k, v) -> if (k != "t" && k != "chat") put(k, v) }
            putJsonArray("items") { chat.items.forEach { add(ChatStore.encodeItem(it, wire = true)) } }
        })
    }

    // ---- decoding helpers --------------------------------------------------------------

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    fun agentOf(name: String?): Agent? = Agent.entries.firstOrNull { it.name == name }

    fun modes(obj: JsonObject): List<SessionMode> = (obj["modes"] as? JsonArray).orEmpty().mapNotNull { el ->
        val m = el as? JsonObject ?: return@mapNotNull null
        SessionMode(m.str("id") ?: return@mapNotNull null, m.str("name").orEmpty(), m.str("description"))
    }
}
