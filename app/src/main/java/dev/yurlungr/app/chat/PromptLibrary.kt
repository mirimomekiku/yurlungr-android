package dev.yurlungr.app.chat

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import org.json.JSONArray
import org.json.JSONObject

/** A reusable prompt: [title] is the label, [text] what gets inserted into the message box. */
data class SavedPrompt(val title: String, val text: String)

/** The user's prompt library (app-private preferences), seeded with a few general-purpose prompts. */
object PromptLibrary {
    private const val PREFS = "prompt_library"
    private val seeds = listOf(
        SavedPrompt("Explain this project", "Give me a short tour of this project: what it does, how it's laid out and how to run it."),
        SavedPrompt("Find and fix bugs", "Review the code for bugs and risky edge cases. Fix the clear ones and list the rest."),
        SavedPrompt("Write tests", "Add focused tests for the code we just changed, then run them."),
        SavedPrompt("Commit this work", "Commit the current changes with a clear message. Don't push."),
        SavedPrompt("Simplify", "Simplify what we just wrote without changing behaviour, then run the tests."),
    )

    fun load(context: Context): List<SavedPrompt> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", null) ?: return seeds
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getJSONObject(it).let { o -> SavedPrompt(o.getString("title"), o.getString("text")) } }
        }.getOrDefault(seeds)
    }

    fun save(context: Context, items: List<SavedPrompt>) {
        val arr = JSONArray().also { a -> items.forEach { a.put(JSONObject().put("title", it.title).put("text", it.text)) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
    }
}

/** Pick a saved prompt to insert, or save what's typed as a new one. */
@Composable
fun PromptLibrarySheet(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val items = remember { mutableStateListOf<SavedPrompt>().apply { addAll(PromptLibrary.load(context)) } }
    var title by remember { mutableStateOf("") }
    AppSheet("Prompts", onDismiss = onDismiss, icon = Ph.ListChecks, tile = AppColors.tiles.lilac, subtitle = "Tap one to insert it") {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(items) { i, p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(p.text); onDismiss() }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall)
                        Text(p.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { items.removeAt(i); PromptLibrary.save(context, items) }) { PhIcon(Ph.Trash, "Delete", size = 18.dp) }
                }
            }
        }
        if (current.isNotBlank()) {
            OutlinedTextField(title, { title = it }, label = { Text("Save what you typed as…") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Button(
                onClick = { items += SavedPrompt(title.trim(), current.trim()); PromptLibrary.save(context, items); title = "" },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text("Save prompt") }
        } else {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Close") }
        }
    }
}

/** Unsent text per chat, kept across restarts. */
object Drafts {
    private fun prefs(c: Context) = c.getSharedPreferences("drafts", Context.MODE_PRIVATE)
    fun get(c: Context, chatId: String): String = prefs(c).getString(chatId, "").orEmpty()
    fun set(c: Context, chatId: String, text: String) =
        prefs(c).edit().apply { if (text.isBlank()) remove(chatId) else putString(chatId, text) }.apply()
}
