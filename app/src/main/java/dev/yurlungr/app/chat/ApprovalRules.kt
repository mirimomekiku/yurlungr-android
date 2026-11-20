package dev.yurlungr.app.chat

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import org.json.JSONArray
import org.json.JSONObject

/** "Allow [kind] tool calls in [scope]" (a project folder, or "*" for every project). */
data class ApprovalRule(val scope: String, val kind: String)

/**
 * Standing answers to permission prompts, so routine reads and searches don't interrupt you.
 * A matching rule approves once, automatically, and says so in the chat; everything else still asks.
 */
object ApprovalRules {
    private const val PREFS = "approval_rules"

    /** Kinds a rule can cover, safest first. "other" is never covered: it can be anything. */
    val kinds = listOf("read", "search", "fetch", "think", "edit", "execute")
    private val risky = setOf("edit", "execute")

    fun isRisky(kind: String) = kind in risky

    fun label(kind: String) = when (kind) {
        "read" -> "Read files"
        "search" -> "Search"
        "fetch" -> "Fetch web pages"
        "think" -> "Think"
        "edit" -> "Edit files"
        "execute" -> "Run commands"
        else -> kind
    }

    fun load(context: Context): List<ApprovalRule> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("rules", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getJSONObject(it).let { o -> ApprovalRule(o.getString("scope"), o.getString("kind")) } }
        }.getOrDefault(emptyList())
    }

    private fun store(context: Context, rules: List<ApprovalRule>) {
        val arr = JSONArray().also { a -> rules.forEach { a.put(JSONObject().put("scope", it.scope).put("kind", it.kind)) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("rules", arr.toString()).apply()
    }

    fun add(context: Context, rule: ApprovalRule) {
        if (rule.kind !in kinds) return
        val rules = load(context)
        if (rule !in rules) store(context, rules + rule)
    }

    fun remove(context: Context, rule: ApprovalRule) = store(context, load(context) - rule)

    /**
     * Kinds a rule covers only when the call names what it acts on, all inside the project. A search
     * that names no folder could search anywhere; a fetch names a URL, which a per-project rule
     * can't judge, so a fetch is approved by a rule only when it reads project files.
     */
    private val pathKinds = setOf("read", "edit", "search", "fetch")

    fun matches(context: Context, project: String, kind: String?, paths: List<String>, policy: PathPolicy): Boolean =
        allows(load(context), project, kind, paths, policy)

    /**
     * Whether [rules] approve a [kind] tool call in [project] that names [paths]. The agent reports
     * the kind and paths itself, so the paths are checked too: a rule for "edit" here must not
     * approve an edit of ~/.bashrc or of the app's own settings.
     */
    fun allows(rules: List<ApprovalRule>, project: String, kind: String?, paths: List<String>, policy: PathPolicy): Boolean {
        if (kind == null || kind !in kinds) return false
        if (rules.none { it.kind == kind && (it.scope == "*" || it.scope == project) }) return false
        if (kind in pathKinds && paths.isEmpty()) return false
        return policy.allowsAutoApproval(java.io.File(project), paths)
    }
}

/** Switches per kind of tool call for this project. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalRulesSheet(project: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val on = remember { mutableStateListOf<String>().apply { addAll(ApprovalRules.load(context).filter { it.scope == project }.map { it.kind }) } }
    AppSheet(
        "Approval rules", onDismiss = onDismiss, icon = Ph.ShieldCheck, tile = AppColors.tiles.peach,
        subtitle = "Auto-approve these in this project",
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (kind in ApprovalRules.kinds) {
                FilterChip(
                    selected = kind in on,
                    onClick = {
                        val rule = ApprovalRule(project, kind)
                        if (kind in on) { on.remove(kind); ApprovalRules.remove(context, rule) } else { on.add(kind); ApprovalRules.add(context, rule) }
                    },
                    label = { Text(ApprovalRules.label(kind) + if (ApprovalRules.isRisky(kind)) " ⚠" else "") },
                )
            }
        }
        Text(
            "Matching requests are approved once without asking, and the chat notes it. ⚠ These can change files or run code. " +
                "Reads, edits, searches and fetches count only when they name files inside this project, and agent settings, " +
                "git hooks and .env files always ask. Anything not switched on still asks you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
