package dev.yurlungr.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.launch
import java.io.File

/** Whether the tour has been shown (it opens once, after the Linux environment is installed). */
object TourPrefs {
    private const val PREFS = "tour"
    fun seen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("seen", false)
    fun markSeen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("seen", true) }
}

private class TourPage(val icon: ImageVector, val title: String, val body: String, val tile: @Composable () -> Tile)

/** A small static web app to try Run, Preview and an agent on (no downloads needed). */
object SampleProject {
    fun create(projects: File): File {
        var dir = File(projects, "welcome-app")
        var i = 2
        while (dir.exists()) dir = File(projects, "welcome-app-${i++}")
        dir.mkdirs()
        File(dir, "index.html").writeText(
            """
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Welcome</title><link rel="stylesheet" href="style.css"></head>
            <body>
              <main>
                <h1>Hello from your phone</h1>
                <p>Edit <code>index.html</code>, or ask an agent to change this page.</p>
                <button id="count">Clicked 0 times</button>
              </main>
              <script src="app.js"></script>
            </body>
            </html>
            """.trimIndent() + "\n",
        )
        File(dir, "style.css").writeText("body { font-family: system-ui, sans-serif; display: grid; place-items: center; min-height: 100vh; margin: 0; background: #f2f3f5; }\nmain { text-align: center; }\nbutton { font-size: 1.1rem; padding: .6rem 1.2rem; border-radius: 12px; border: 0; background: #0f172a; color: white; }\n")
        File(dir, "app.js").writeText("let n = 0\nconst b = document.getElementById('count')\nb.addEventListener('click', () => { n++; b.textContent = `Clicked ${'$'}{n} time${'$'}{n === 1 ? '' : 's'}` })\n")
        File(dir, "AGENTS.md").writeText("# Welcome app\n\nA static page: index.html, style.css and app.js. No build step; Run serves it as is.\n")
        File(dir, "README.md").writeText("# Welcome app\n\nTap **Run** to serve it and open the preview. Try asking an agent: \"make the button green and count down from 10\".\n")
        return dir
    }
}

/**
 * The first-run tour: where things are (projects and the dock, search everything, checks,
 * servers and preview, the development environment), and a sample project to try them on.
 */
@Composable
fun TourScreen(onDone: () -> Unit, onSample: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = listOf(
        TourPage(Ph.FolderSimple, "Projects", "Each project has Files, Changes, History, Checks and Chats. The dock below holds Chat, the main action (Run or Build) and More: scripts, setup, git and agent settings.") { AppColors.tiles.lime },
        TourPage(Ph.MagnifyingGlass, "Search everything", "Long-press ☰ or ‹ on any screen to jump to a screen, project, file, chat or script.") { AppColors.tiles.sky },
        TourPage(Ph.ShieldCheck, "Checks and checkpoints", "Checks runs a project's compile checks and tests and lists what failed. Every agent prompt is a checkpoint: open a chat's ⋮ → Checkpoints to see what it changed, or undo it.") { AppColors.tiles.lilac },
        TourPage(Ph.Globe, "Run and preview", "Run starts a web app and opens it inside the app, with its console. Servers (in the sidebar) lists what's running and listening.") { AppColors.tiles.peach },
        TourPage(Ph.Wrench, "Your environment", "Settings → Development environment: the default JDK, runtime packs (Node, Python, Rust, Go and 40 more), storage and updates.") { AppColors.tiles.sky },
    )
    val pager = rememberPagerState { pages.size }
    fun finish() { TourPrefs.markSeen(context); onDone() }
    BackHandler { if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else finish() }
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = ::finish) { Text("Skip") } }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
            val page = pages[i]
            Column(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val tile = page.tile()
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(32.dp)).background(tile.fill), contentAlignment = Alignment.Center) {
                    PhIcon(page.icon, null, size = 44.dp, tint = tile.on)
                }
                Spacer(Modifier.height(28.dp))
                Text(page.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(page.body, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 7.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) colors.primary else colors.outlineVariant))
            }
        }
        if (pager.currentPage == pages.lastIndex) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = ::finish, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Done") }
                Button(
                    onClick = {
                        val projects = dev.yurlungr.app.runtime.RuntimePaths(context).projects.apply { mkdirs() }
                        runCatching { SampleProject.create(projects) }.onSuccess { TourPrefs.markSeen(context); onSample(it) }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text("Try a sample project") }
            }
        } else {
            Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Next") }
        }
        Spacer(Modifier.width(1.dp))
    }
}
