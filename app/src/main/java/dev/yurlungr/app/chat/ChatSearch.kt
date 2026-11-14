package dev.yurlungr.app.chat

/** A match inside a chat's messages: the chat, what was said, and who said it. */
data class ChatHit(val chat: ChatSession, val snippet: String, val fromUser: Boolean)

object ChatSearch {
    /** Messages (yours and the agent's) containing [query] across all chats, newest chats first; one hit per chat message. */
    fun search(chats: List<ChatSession>, query: String, limit: Int = 30): List<ChatHit> {
        val q = query.trim().lowercase()
        if (q.length < 3) return emptyList()
        val hits = ArrayList<ChatHit>()
        for (chat in chats.sortedByDescending { it.updatedAt }) {
            var inChat = 0
            for (item in chat.items.toList().asReversed()) {
                val (text, user) = when (item) {
                    is ChatItem.User -> item.text to true
                    is ChatItem.AgentText -> item.text to false
                    else -> continue
                }
                val at = text.lowercase().indexOf(q)
                if (at < 0) continue
                hits += ChatHit(chat, snippet(text, at, q.length), user)
                if (++inChat >= 3 || hits.size >= limit) break
            }
            if (hits.size >= limit) break
        }
        return hits
    }

    /** A one-line excerpt around the match. */
    fun snippet(text: String, at: Int, length: Int): String {
        val from = (at - 40).coerceAtLeast(0)
        val to = (at + length + 60).coerceAtMost(text.length)
        return (if (from > 0) "…" else "") + text.substring(from, to).replace(Regex("\\s+"), " ").trim() + (if (to < text.length) "…" else "")
    }
}
