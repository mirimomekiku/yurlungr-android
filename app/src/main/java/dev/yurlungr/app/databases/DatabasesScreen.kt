package dev.yurlungr.app.databases

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.env.EnvStore
import dev.yurlungr.app.env.GitExposure
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Group
import dev.yurlungr.app.ui.GroupRow
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.SectionLabel
import dev.yurlungr.app.ui.WarningBanner
import dev.yurlungr.app.ui.WaveDots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Copies a secret: Android 13+ keeps it out of the clipboard preview. */
private fun copySecret(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/**
 * A project's own PostgreSQL, Redis and MariaDB: start/stop, the connection string (password
 * hidden until asked), copy it, or write it to `.env`. Points to System checks when the
 * Databases pack isn't installed.
 */
@Composable
fun DatabasesScreen(project: File, onBack: () -> Unit, onSystemChecks: () -> Unit, onOpenFile: (File) -> Unit, onEnvFile: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var servers by remember { mutableStateOf<Map<DbKind, DbServer?>?>(null) }
    var installed by remember { mutableStateOf<Map<DbKind, Boolean>>(emptyMap()) }
    var remembered by remember { mutableStateOf<DbProjectConfig?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    // Whether git would commit .env (Tracked: it already is); asked once on open and after each write.
    var envExposure by remember { mutableStateOf(GitExposure.Unknown) }
    // "Add to .env" for a .env that's committed: waits for the user to choose.
    var confirmTracked by remember { mutableStateOf<Pair<DbKind, DbServer>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(project) {
        if (withContext(Dispatchers.IO) { File(project, ".env").exists() }) envExposure = EnvStore.exposure(paths, project, ".env")
    }
    val revealed = remember { mutableStateMapOf<DbKind, String>() } // kind → full URL while shown
    val database = remember(project) { ProjectDatabases.databaseName(project) }
    val keys = remember(project) { DbKind.entries.associateWith { ProjectDatabases.key(project, it) } }

    LaunchedEffect(project, reload) {
        while (true) {
            installed = withContext(Dispatchers.IO) { DbKind.entries.associateWith { ProjectDatabases.installed(paths, it) } }
            remembered = withContext(Dispatchers.IO) {
                File(ProjectDatabases.folder(paths, project), "db.properties").takeIf { it.isFile }
                    ?.let { runCatching { DbProjectConfig.parse(it.readText()) }.getOrNull() }
            }
            servers = ProjectDatabases.refresh(paths, project)
            delay(4_000)
        }
    }

    fun act(kind: DbKind, block: suspend () -> Result<String>) {
        message = null
        revealed.remove(kind)
        ProjectDatabases.scope.launch {
            block().onSuccess { message = it to false }.onFailure { message = (it.message ?: it.toString()) to true }
            reload++
        }
    }

    /** Writes [kind]'s connection string (password included) to .env. */
    suspend fun writeEnv(kind: DbKind, server: DbServer) {
        val result = withContext(Dispatchers.IO) {
            runCatching { EnvStore.upsert(File(project, ".env"), kind.envKey, ProjectDatabases.url(paths, server)) }
        }
        result.onSuccess { replaced ->
            message = (if (replaced) "Updated ${kind.envKey} in .env" else "Added ${kind.envKey} to .env") to false
            envExposure = EnvStore.exposure(paths, project, ".env")
        }.onFailure { message = "Couldn't write .env: ${it.message}" to true }
    }

    confirmTracked?.let { (kind, server) ->
        AlertDialog(
            onDismissRequest = { confirmTracked = null },
            title = { Text(".env is committed") },
            text = {
                Text("Git already tracks .env, so the database password would go into your next commit and to anyone who can see the repository. " +
                    "Stop tracking it first (the file stays here and is added to .gitignore), or write it anyway?")
            },
            confirmButton = {
                Button(onClick = {
                    confirmTracked = null
                    scope.launch {
                        EnvStore.untrack(paths, project, ".env")
                            .onSuccess { writeEnv(kind, server) }
                            .onFailure { message = (it.message ?: "Couldn't untrack .env") to true }
                    }
                }) { Text("Untrack and add") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmTracked = null }) { Text("Cancel") }
                    TextButton(onClick = { confirmTracked = null; scope.launch { writeEnv(kind, server) } }) { Text("Add anyway", color = colors.error) }
                }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        ScreenHeader("Databases", onBack) {
            IconButton(onClick = { reload++ }) { PhIcon(Ph.ArrowClockwise, "Refresh", size = 20.dp) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            message?.let { (text, isError) ->
                item {
                    Text(text, style = MaterialTheme.typography.bodySmall, color = if (isError) colors.error else colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
            if (envExposure == GitExposure.NotIgnored) item {
                WarningBanner(".env isn't ignored by git, so the password could be committed.") {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { EnvStore.ignore(project, ".env") } }
                            envExposure = EnvStore.exposure(paths, project, ".env")
                        }
                    }) { Text("Ignore it") }
                }
            }
            if (envExposure == GitExposure.Tracked) item {
                WarningBanner(".env is already committed, so a password written to it goes into the next commit. Stop tracking it (the file stays here).") {
                    TextButton(onClick = {
                        scope.launch {
                            EnvStore.untrack(paths, project, ".env").onFailure { message = (it.message ?: "Couldn't untrack .env") to true }
                            envExposure = EnvStore.exposure(paths, project, ".env")
                        }
                    }) { Text("Untrack") }
                }
            }
            for (kind in DbKind.entries) {
                val server = servers?.get(kind)
                val busy = ProjectDatabases.busy[keys.getValue(kind)]
                val have = installed[kind]
                item { SectionLabel(kind.title) }
                item {
                    Group {
                        val port = server?.port ?: remembered?.port(kind)
                        GroupRow(
                            kind.title,
                            subtitle = when {
                                busy != null -> busy
                                servers == null -> "Checking…"
                                server != null -> "Running · 127.0.0.1:${server.port} · database $database"
                                have == false -> "Not installed. Install the Databases pack in System checks."
                                else -> "Stopped" + (port?.let { " · port $it" } ?: " · first start sets it up")
                            },
                            subtitleLines = 2,
                            icon = Ph.FloppyDisk,
                            tile = if (server != null) AppColors.tiles.lime else null,
                            trailing = {
                                when {
                                    busy != null -> WaveDots(dotSize = 4.dp)
                                    servers == null -> {}
                                    server != null -> TextButton(onClick = { act(kind) { ProjectDatabases.stop(context, project, kind) } }) { Text("Stop") }
                                    have == false -> TextButton(onClick = onSystemChecks) { Text("System checks") }
                                    else -> Button(onClick = { act(kind) { ProjectDatabases.start(context, project, kind) } }) { Text("Start") }
                                }
                            },
                        )
                        if (server != null) {
                            val full = revealed[kind]
                            GroupRow(
                                full ?: kind.masked(server.port, server.database),
                                subtitle = "Connection string",
                                trailing = {
                                    IconButton(onClick = {
                                        if (full != null) revealed.remove(kind)
                                        else scope.launch { revealed[kind] = withContext(Dispatchers.IO) { ProjectDatabases.url(paths, server) } }
                                    }) { PhIcon(Ph.Eye, if (full != null) "Hide password" else "Show password", size = 18.dp, tint = if (full != null) colors.primary else colors.onSurfaceVariant) }
                                    IconButton(onClick = {
                                        scope.launch {
                                            val url = withContext(Dispatchers.IO) { runCatching { ProjectDatabases.url(paths, server) }.getOrNull() }
                                            if (url != null) {
                                                copySecret(context, kind.envKey, url)
                                                Toast.makeText(context, "Connection string copied", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }) { PhIcon(Ph.Copy, "Copy connection string", size = 18.dp) }
                                },
                            )
                            GroupRow("Add to .env", subtitle = "Sets ${kind.envKey}; the rest of .env stays as it is", icon = Ph.LockSimple, onClick = {
                                scope.launch {
                                    // Asked fresh: a commit since the screen opened may have added .env.
                                    val exposure = EnvStore.exposure(paths, project, ".env").also { envExposure = it }
                                    if (exposure == GitExposure.Tracked) confirmTracked = kind to server
                                    else writeEnv(kind, server)
                                }
                            })
                        }
                        val log = File(ProjectDatabases.folder(paths, project), kind.logFile)
                        if (server != null || port != null) GroupRow("Log", subtitle = "${kind.logFile} · what the server printed", icon = Ph.FileText, onClick = { onOpenFile(log) })
                    }
                }
            }
            item {
                Group {
                    GroupRow(".env editor", subtitle = "See and edit this project's variables", icon = Ph.LockSimple, onClick = onEnvFile)
                }
            }
            item {
                Text(
                    "Each project gets its own servers, data and ports. Data lives in ~/.local/share/yurlungr/databases, outside the project, " +
                        "so it's never committed. Servers listen on 127.0.0.1 only, with a password, keep running while you use other apps, " +
                        "and stop with Exit in the notification.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** A slim card on the project screen while any of its databases run; tap for the Databases screen. */
@Composable
fun ProjectDatabasesStrip(project: File, onOpen: () -> Unit) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val keys = remember(project) { DbKind.entries.associateBy { ProjectDatabases.key(project, it) } }
    LaunchedEffect(project) { ProjectDatabases.refresh(paths, project) }
    val running = ProjectDatabases.running.filterKeys { it in keys }.values.sortedBy { it.kind.ordinal }
    if (running.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val tile = AppColors.tiles.lime
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape).background(colors.surfaceContainer).border(1.dp, colors.outlineVariant, shape)
            .clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(11.dp)).background(tile.fill), contentAlignment = Alignment.Center) {
            PhIcon(Ph.FloppyDisk, null, size = 16.dp, tint = tile.on)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(running.joinToString(" · ") { "${it.kind.title} :${it.port}" }, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(AppColors.status.success))
                Spacer(Modifier.width(6.dp))
                Text("Running", style = MaterialTheme.typography.labelMedium, color = AppColors.status.success)
            }
        }
        PhIcon(Ph.CaretRight, null, size = 14.dp, tint = colors.outline)
    }
}

/** The Servers screen's list of project databases (nothing when none run). */
@Composable
fun DatabaseServersSection() {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) {
            if (paths.isBootstrapInstalled) ProjectDatabases.refreshAll(paths)
            delay(5_000)
        }
    }
    val servers = ProjectDatabases.running.values.sortedWith(compareBy({ it.project.name }, { it.kind.ordinal }))
    if (servers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Databases")
        Group {
            for (s in servers) {
                val busy = ProjectDatabases.busy[s.key]
                GroupRow(
                    "${s.kind.title} · ${s.project.name}",
                    subtitle = busy ?: "127.0.0.1:${s.port} · database ${s.database}",
                    icon = Ph.FloppyDisk,
                    tile = AppColors.tiles.lime,
                    trailing = {
                        IconButton(onClick = {
                            scope.launch {
                                val url = withContext(Dispatchers.IO) { runCatching { ProjectDatabases.url(paths, s) }.getOrNull() }
                                if (url != null) {
                                    copySecret(context, s.kind.envKey, url)
                                    Toast.makeText(context, "Connection string copied", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) { PhIcon(Ph.Copy, "Copy connection string", size = 16.dp) }
                        if (busy == null) IconButton(onClick = {
                            ProjectDatabases.scope.launch {
                                ProjectDatabases.stop(context, s.project, s.kind).onFailure {
                                    Toast.makeText(context, it.message ?: "Couldn't stop it", Toast.LENGTH_LONG).show()
                                }
                            }
                        }) { PhIcon(Ph.Stop, "Stop", size = 18.dp, tint = MaterialTheme.colorScheme.error) }
                    },
                )
            }
        }
    }
}
