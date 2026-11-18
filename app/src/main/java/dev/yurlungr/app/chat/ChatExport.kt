package dev.yurlungr.app.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A chat written out for keeping or sharing: Markdown to read, JSON for tools. Also the digest a
 * fresh chat starts from when the old one's context is full.
 */
object ChatExport {
    private val stamp get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** A safe file name from the chat's title, e.g. "Fix the login bug.md". */
    fun fileName(title: String, extension: String): String {
        val base = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(60).trimEnd('.', ' ')
        return (base.ifEmpty { "chat" }) + "." + extension
    }

    fun markdown(title: String, agent: String, folder: String, items: List<ChatItem>, exportedAt: Long = System.currentTimeMillis()): String = buildString {
        append("# ").append(title.trim().ifEmpty { "Chat" }).append("\n\n")
        append("_").append(agent).append(" · ").append(folder).append(" · exported ").append(stamp.format(Date(exportedAt))).append("_\n")
        var speaker: String? = null
        fun heading(who: String) {
            if (speaker == who) return
            speaker = who
            append("\n## ").append(who).append("\n\n")
        }
        for (item in items) when (item) {
            is ChatItem.User -> {
                speaker = null // every prompt starts a new section, even two in a row
                heading("You")
                if (item.text.isNotBlank()) append(item.text.trim()).append("\n")
                if (item.attachments.isNotEmpty()) append("\n_Attached: ").append(item.attachments.joinToString(", ")).append("_\n")
            }
            is ChatItem.AgentText -> if (item.text.isNotBlank()) {
                heading(agent)
                append(item.text.trim()).append("\n\n")
            }
            is ChatItem.Thought -> if (item.text.isNotBlank()) {
                heading(agent)
                append("<details><summary>Thinking</summary>\n\n").append(item.text.trim()).append("\n\n</details>\n\n")
            }
            is ChatItem.ToolCall -> {
                heading(agent)
                append("**").append(toolLabel(item.kind)).append("** · ").append(item.title.ifBlank { item.toolCallId }.oneLine())
                append(" · _").append(item.status.replace('_', ' ')).append("_\n")
                if (item.locations.isNotEmpty()) append("\n").append(item.locations.joinToString(", ") { "`$it`" }).append("\n")
                for (c in item.content) when (c) {
                    is ToolContent.Text -> if (c.text.isNotBlank()) append("\n").append(fenced(c.text.take(MAX_OUTPUT), ""))
                    is ToolContent.Diff -> append("\n").append(fenced(changedLines(c).joinToString("\n"), "diff"))
                    is ToolContent.Terminal -> append("\n_Terminal ").append(c.terminalId).append("_\n")
                }
                append("\n")
            }
            is ChatItem.Plan -> {
                heading(agent)
                append("**Plan**\n\n")
                item.entries.forEach { append("- [").append(if (it.status == "completed") "x" else " ").append("] ").append(it.content.oneLine()).append("\n") }
                append("\n")
            }
            is ChatItem.Permission -> {
                heading(agent)
                append("> Permission: ").append(item.toolTitle.oneLine()).append(" → ").append(item.resolved ?: "waiting").append("\n\n")
            }
            is ChatItem.Notice -> {
                append("> _").append(item.text.oneLine()).append("_\n\n")
            }
        }
    }.trimEnd() + "\n"

    fun json(title: String, agent: String, folder: String, items: List<ChatItem>, exportedAt: Long = System.currentTimeMillis()): String =
        buildJsonObject {
            put("format", "yurlungr-chat")
            put("version", 1)
            put("title", title)
            put("agent", agent)
            put("folder", folder)
            put("exportedAt", exportedAt)
            put("items", JsonArray(items.map { ChatStore.encodeItem(it) }))
        }.toString()

    /**
     * What happened so far, for a fresh chat to continue from: each prompt with the agent's
     * reply and the files it touched, newest kept when it doesn't all fit in [maxChars].
     */
    fun digest(items: List<ChatItem>, folder: String, maxChars: Int = 6_000): String {
        class Turn(val prompt: String) {
            var reply = ""
            val files = LinkedHashSet<String>()
        }
        val turns = ArrayList<Turn>()
        for (item in items) when (item) {
            is ChatItem.User -> turns += Turn(item.text.trim().ifEmpty { item.attachments.joinToString(", ") })
            is ChatItem.AgentText -> turns.lastOrNull()?.let { if (item.text.isNotBlank()) it.reply = item.text.trim() }
            is ChatItem.ToolCall -> turns.lastOrNull()?.let { t ->
                if (item.kind == "edit" || item.kind == "delete" || item.kind == "move") {
                    item.content.filterIsInstance<ToolContent.Diff>().forEach { t.files += it.path.substringAfterLast('/') }
                    item.locations.forEach { t.files += it.substringBefore(':').substringAfterLast('/') }
                }
            }
            else -> Unit
        }
        val plan = (items.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan)?.entries.orEmpty()
        val head = "We're continuing an earlier chat in $folder whose context ran full. Here's what happened so far:\n\n"
        val tail = buildString {
            if (plan.isNotEmpty()) {
                append("\nPlan at that point:\n")
                plan.forEach { append("- [").append(if (it.status == "completed") "x" else " ").append("] ").append(it.content.oneLine()).append("\n") }
            }
            append("\nPick up from where this leaves off. Look at the files again before changing them; ask me if anything is unclear.")
        }
        // Room kept for the "(N earlier exchanges left out)" line.
        val budget = maxChars - head.length - tail.length - 48
        val parts = ArrayList<String>()
        var used = 0
        for (t in turns.asReversed()) {
            val part = buildString {
                append("Me: ").append(t.prompt.clip(500)).append("\n")
                if (t.reply.isNotEmpty()) append("You: ").append(t.reply.clip(800)).append("\n")
                if (t.files.isNotEmpty()) append("Files changed: ").append(t.files.take(12).joinToString(", ")).append("\n")
            }
            if (used + part.length + 1 > budget) {
                if (parts.isEmpty()) parts += part.take(budget.coerceAtLeast(0))
                break
            }
            parts += part
            used += part.length + 1
        }
        val skipped = turns.size - parts.size
        return head + (if (skipped > 0) "(${skipped} earlier exchange${if (skipped == 1) "" else "s"} left out)\n\n" else "") +
            parts.asReversed().joinToString("\n") + tail
    }

    /** The changed region of a diff as -/+ lines (common leading and trailing lines left out). */
    fun changedLines(diff: ToolContent.Diff, limit: Int = 300): List<String> {
        val old = diff.oldText?.lines().orEmpty()
        val new = diff.newText.lines()
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix && old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) suffix++
        val removed = old.subList(prefix, old.size - suffix).map { "-$it" }
        val added = new.subList(prefix, new.size - suffix).map { "+$it" }
        return listOf("@@ ${diff.path}:${prefix + 1}") + (removed + added).take(limit)
    }

    /** A code fence longer than any run of backticks inside [text], so the text can't close it. */
    internal fun fenced(text: String, language: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        return "$fence$language\n${text.trimEnd('\n')}\n$fence\n"
    }

    private fun toolLabel(kind: String) = when (kind) {
        "read" -> "Read"; "edit" -> "Edit"; "delete" -> "Delete"; "move" -> "Move"; "search" -> "Search"
        "execute" -> "Run"; "think" -> "Think"; "fetch" -> "Fetch"; else -> "Tool"
    }

    private fun String.oneLine() = replace(Regex("\\s*\\n\\s*"), " ").trim()
    private fun String.clip(max: Int) = if (length <= max) this else take(max).trimEnd() + "…"

    private const val MAX_OUTPUT = 8_000
}
