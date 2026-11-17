package dev.yurlungr.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import java.io.File

/** A piece of code to insert; `|` marks where the cursor goes (written `||` for a literal bar). */
data class Snippet(val label: String, val body: String)

/** Snippets per file type and templates for new files. */
object Snippets {
    private val KT = listOf(
        Snippet("fun", "fun |name() {\n    \n}"), Snippet("class", "class |Name {\n}"), Snippet("data class", "data class |Name(val id: String)"),
        Snippet("when", "when (|) {\n    else -> {}\n}"), Snippet("@Composable", "@Composable\nfun |Name() {\n}"), Snippet("test", "@Test fun `|does something`() {\n}"),
    )
    private val TS = listOf(
        Snippet("function", "function |name() {\n  \n}"), Snippet("arrow", "const |name = () => {\n  \n}"), Snippet("async", "async function |name() {\n  \n}"),
        Snippet("import", "import { | } from ''"), Snippet("component", "export function |Component() {\n  return <div></div>\n}"),
        Snippet("test", "test('|does something', () => {\n  expect(true).toBe(true)\n})"), Snippet("try", "try {\n  |\n} catch (error) {\n  console.error(error)\n}"),
    )
    private val PY = listOf(
        Snippet("def", "def |name():\n    pass"), Snippet("class", "class |Name:\n    def __init__(self):\n        pass"),
        Snippet("main", "if __name__ == \"__main__\":\n    |main()"), Snippet("test", "def test_|thing():\n    assert True"),
        Snippet("with open", "with open(|\"file.txt\") as f:\n    data = f.read()"),
    )
    private val GO = listOf(
        Snippet("func", "func |name() {\n}"), Snippet("if err", "if err != nil {\n\treturn |err\n}"), Snippet("struct", "type |Name struct {\n}"),
        Snippet("test", "func Test|Thing(t *testing.T) {\n}"),
    )
    private val RS = listOf(
        Snippet("fn", "fn |name() {\n}"), Snippet("struct", "struct |Name {\n}"), Snippet("impl", "impl |Name {\n}"),
        Snippet("match", "match | {\n    _ => {}\n}"), Snippet("test", "#[test]\nfn |works() {\n}"),
    )
    private val HTML = listOf(Snippet("div", "<div>|</div>"), Snippet("link", "<a href=\"|\"></a>"), Snippet("script", "<script type=\"module\">\n  |\n</script>"))
    private val SH = listOf(Snippet("if", "if [ | ]; then\n  \nfi"), Snippet("for", "for f in |*; do\n  echo \"\$f\"\ndone"), Snippet("strict", "set -euo pipefail\n|"))
    private val MD = listOf(Snippet("code block", "```|\n\n```"), Snippet("link", "[|](url)"), Snippet("table", "| A | B |\n|---|---|\n| 1 | 2 |"))

    fun forFile(name: String): List<Snippet> = when (name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> KT
        "ts", "tsx", "js", "jsx", "mjs", "cjs" -> TS
        "py" -> PY
        "go" -> GO
        "rs" -> RS
        "html", "htm", "vue", "svelte" -> HTML
        "sh", "bash" -> SH
        "md", "markdown" -> MD
        else -> emptyList()
    }

    /** [body] with the cursor marker removed, and where the cursor goes. */
    fun expand(body: String): Pair<String, Int> {
        val out = StringBuilder()
        var cursor = -1
        var i = 0
        while (i < body.length) {
            when {
                body.startsWith("||", i) -> { out.append('|'); i += 2 }
                body[i] == '|' && cursor < 0 -> { cursor = out.length; i++ }
                else -> { out.append(body[i]); i++ }
            }
        }
        return out.toString() to (if (cursor < 0) out.length else cursor)
    }

    /** A starting point for a new file named [name] (by extension), or empty. */
    fun template(name: String): String {
        val base = name.substringAfterLast('/').substringBeforeLast('.')
        val type = base.replaceFirstChar { it.uppercase() }.replace(Regex("[^A-Za-z0-9]"), "")
        return when (name.substringAfterLast('.', "").lowercase()) {
            "kt" -> "class $type {\n}\n"
            "java" -> "public class $type {\n}\n"
            "ts", "js", "mjs" -> "export function $base() {\n}\n"
            "tsx", "jsx" -> "export function $type() {\n  return <div>$type</div>\n}\n"
            "py" -> "def main():\n    pass\n\n\nif __name__ == \"__main__\":\n    main()\n"
            "go" -> "package main\n\nfunc main() {\n}\n"
            "rs" -> "fn main() {\n}\n"
            "html" -> "<!doctype html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"utf-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n  <title>$type</title>\n</head>\n<body>\n</body>\n</html>\n"
            "css" -> ":root {\n}\n"
            "md" -> "# $type\n"
            "json" -> "{\n}\n"
            "sh" -> "#!/usr/bin/env bash\nset -euo pipefail\n"
            "yml", "yaml" -> "# $type\n"
            else -> ""
        }
    }
}

/** The snippets for this file type; tap one to insert it at the cursor. */
@Composable
fun SnippetSheet(fileName: String, onDismiss: () -> Unit, onInsert: (Snippet) -> Unit) {
    val list = Snippets.forFile(fileName)
    val colors = MaterialTheme.colorScheme
    AppSheet("Insert snippet", onDismiss = onDismiss, icon = Ph.Plus, tile = AppColors.tiles.lime) {
        if (list.isEmpty()) Text("No snippets for this kind of file yet.", color = colors.onSurfaceVariant)
        for (s in list) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh).clickable { onInsert(s) }.padding(10.dp)) {
                Text(s.label, style = MaterialTheme.typography.titleSmall)
                Text(Snippets.expand(s.body).first, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Name a new file (folders are created), started from a template for its type. */
@Composable
fun NewFileSheet(root: File, onDismiss: () -> Unit, onCreated: (File) -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val target = File(root, name.trim())
    // Resolved off the main thread (canonical paths and exists() touch the disk on every keystroke).
    val check by androidx.compose.runtime.produceState(false to false, name) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { (name.isNotBlank() && target.canonicalPath.startsWith(root.canonicalPath + File.separator)) to target.exists() }.getOrDefault(false to false)
        }
    }
    val (inside, exists) = check
    AppSheet(
        "New file", onDismiss = onDismiss, icon = Ph.FileText, tile = AppColors.tiles.sky, subtitle = "In ${root.name}. Use / for folders, like src/utils/date.ts.",
        actions = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel") }
            Button(enabled = inside && !exists, modifier = Modifier.weight(1f).heightIn(min = 52.dp), onClick = {
                runCatching {
                    target.parentFile?.mkdirs()
                    target.writeText(Snippets.template(target.name))
                }.onSuccess { onCreated(target) }.onFailure { error = it.message }
            }) { Text("Create") }
        },
    ) {
        OutlinedTextField(name, { name = it; error = null }, label = { Text("Path") }, singleLine = true,
            isError = name.isNotBlank() && (!inside || target.exists()),
            supportingText = { Text(error ?: when { !inside && name.isNotBlank() -> "Must stay inside the project"; target.exists() && name.isNotBlank() -> "Already exists"; else -> "Started from a template for its type" }) },
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
        val preview = Snippets.template(name.trim())
        if (preview.isNotEmpty()) Text(preview, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp))
    }
}
