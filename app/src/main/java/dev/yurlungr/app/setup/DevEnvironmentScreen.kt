package dev.yurlungr.app.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.Jdk
import dev.yurlungr.app.runtime.Jdks
import dev.yurlungr.app.runtime.RuntimeEnv
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.runtime.ToolSetup
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Group
import dev.yurlungr.app.ui.GroupRow
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.SectionLabel
import dev.yurlungr.app.ui.WaveDots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the screen shows, read off the main thread. */
private data class DevEnvState(
    val jdks: List<Jdk>,
    val current: Jdk?,
    val manual: Boolean,
    val gradleSynced: Boolean,
    val env: Map<String, String>,
    val packs: Map<ToolSetup, Boolean>,
)

private fun readState(paths: RuntimePaths): DevEnvState {
    val jdks = Jdks.installed(paths.prefix)
    val props = File(paths.home, ".gradle/gradle.properties").let { if (it.isFile) it.readText() else "" }
    return DevEnvState(
        jdks = jdks,
        current = Jdks.current(paths.prefix),
        manual = Jdks.isManual(paths.prefix),
        gradleSynced = jdks.isEmpty() || Jdks.withGradleToolchains(props, jdks) == props,
        env = RuntimeEnv.map(paths),
        packs = ToolSetup.entries.filter { it.optional }.associateWith { p -> p.marker?.let { File(paths.prefix, it).exists() } == true },
    )
}

/**
 * Settings → Development environment: the default JDK (any installed version, or automatic),
 * other JDKs to install, Gradle's toolchain list, the environment every process gets (JAVA_HOME,
 * PATH and the rest), and the optional runtime packs.
 */
@Composable
fun DevEnvironmentScreen(
    refreshTick: Int,
    onBack: () -> Unit,
    onSystemChecks: () -> Unit,
    onInstallSetup: (ToolSetup) -> Unit,
    runInTerminal: (command: String, name: String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<DevEnvState?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshTick, reload) {
        state = withContext(Dispatchers.IO) {
            // A JDK installed since the last launch: let Gradle know about it right away.
            runCatching { Jdks.syncGradle(paths) }
            readState(paths)
        }
    }
    fun copy(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
    fun switch(label: String, command: List<String>, done: String) {
        if (busy != null) return
        busy = label
        scope.launch {
            val r = CommandRunner.run(paths, command, timeoutMs = 30_000)
            if (r.ok) withContext(Dispatchers.IO) { runCatching { Jdks.syncGradle(paths) } }
            Toast.makeText(context, if (r.ok) done else r.describe("Switching the JDK"), Toast.LENGTH_LONG).show()
            busy = null
            reload++
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        ScreenHeader("Development environment", onBack) {
            IconButton(onClick = { reload++ }, enabled = busy == null) {
                if (busy != null) WaveDots(dotSize = 4.dp) else PhIcon(Ph.ArrowClockwise, "Refresh", size = 20.dp)
            }
        }
        val s = state
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (s == null) return@LazyColumn
            item { SectionLabel("Java (JDK)") }
            item {
                Group {
                    GroupRow(
                        "Automatic",
                        subtitle = "Always the newest installed JDK" + (if (!s.manual) s.current?.let { " · now Java ${it.version}" }.orEmpty() else ""),
                        leading = { RadioButton(selected = !s.manual, onClick = null) },
                        onClick = if (s.manual && s.jdks.isNotEmpty()) ({ switch("auto", Jdks.autoCommand(paths.prefix), "The newest JDK is the default again") }) else null,
                        trailing = {},
                    )
                    for (v in Jdks.AVAILABLE) {
                        val jdk = s.jdks.firstOrNull { it.version == v }
                        val isDefault = jdk != null && s.current == jdk
                        GroupRow(
                            "Java $v",
                            subtitle = when {
                                jdk == null -> "Not installed" + if (v == Jdks.REQUIRED) " · needed by Gradle, Kotlin, Maven, d8 and apksigner" else ""
                                v == Jdks.REQUIRED -> "Needed by Gradle, Kotlin, Maven, d8 and apksigner"
                                else -> jdk.home.path.removePrefix(paths.prefix.path).let { "\$PREFIX$it" }
                            },
                            leading = {
                                RadioButton(selected = s.manual && isDefault, onClick = null, enabled = jdk != null)
                            },
                            onClick = if (jdk != null && !(s.manual && isDefault)) ({
                                switch("java$v", Jdks.useCommand(paths.prefix, jdk), "Java $v is now the default. New terminals and chats use it.")
                            }) else null,
                            trailing = {
                                when {
                                    jdk == null -> TextButton(onClick = { runInTerminal("pkg install -y ${Jdks.pkg(v)}", "Install Java $v") }) { Text("Install") }
                                    isDefault -> Badge("Default", AppColors.status.success)
                                }
                            },
                        )
                    }
                }
            }
            item {
                Text(
                    "The default sets java, javac and JAVA_HOME for terminals, agents and builds. Terminals and chats already open keep " +
                        "the JDK they started with. A project can also ask Gradle for a specific version (below).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }

            item { SectionLabel("Gradle toolchains") }
            item {
                Group {
                    GroupRow(
                        if (s.jdks.isEmpty()) "No JDK installed" else "Gradle finds Java ${s.jdks.joinToString { it.version.toString() }}",
                        subtitle = "For projects that pin a JDK, like jvmToolchain(17). Written to ~/.gradle/gradle.properties, with auto-download off (there are no Android builds to download).",
                        icon = Ph.Hammer,
                        tile = AppColors.tiles.lime,
                        trailing = {
                            if (s.gradleSynced) Badge("Up to date", AppColors.status.success)
                            else TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { Jdks.syncGradle(paths) } }; reload++ } }) { Text("Update") }
                        },
                    )
                }
            }

            item { SectionLabel("Environment") }
            item {
                Group {
                    for (key in listOf("JAVA_HOME", "ANDROID_HOME", "PNPM_HOME", "GOPATH", "CARGO_HOME", "DOTNET_ROOT")) {
                        val value = s.env[key] ?: continue
                        GroupRow(
                            key,
                            subtitle = value.replace(paths.home.path, "~").replace(paths.prefix.path, "\$PREFIX"),
                            onClick = { copy(key, value) },
                            trailing = { PhIcon(Ph.Copy, "Copy", size = 15.dp, tint = MaterialTheme.colorScheme.outline) },
                        )
                    }
                    GroupRow("PATH", subtitle = "In this order. Folders are created when a tool first installs into them.", trailing = {
                        TextButton(onClick = {
                            val exports = listOf("JAVA_HOME", "ANDROID_HOME", "PNPM_HOME", "GOPATH", "CARGO_HOME", "DOTNET_ROOT", "PATH")
                                .mapNotNull { k -> s.env[k]?.let { "export $k=\"$it\"" } }.joinToString("\n")
                            copy("environment", exports)
                        }) { Text("Copy exports") }
                    })
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (dir in s.env["PATH"].orEmpty().split(':').filter { it.isNotBlank() }) {
                            val exists = File(dir).isDirectory
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(if (exists) AppColors.status.success else MaterialTheme.colorScheme.outlineVariant))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    dir.replace(paths.home.path, "~").replace(paths.prefix.path, "\$PREFIX"),
                                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    color = if (exists) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item { SectionLabel("Runtime packs") }
            item {
                Group {
                    for ((pack, installed) in s.packs) {
                        GroupRow(
                            pack.title,
                            subtitle = pack.blurb,
                            subtitleLines = 2,
                            trailing = {
                                if (installed) Badge("Installed", AppColors.status.success)
                                else TextButton(onClick = { onInstallSetup(pack) }) { Text("Install") }
                            },
                        )
                    }
                    GroupRow("System checks", subtitle = "Versions and problems for everything installed", icon = Ph.ListChecks, onClick = onSystemChecks)
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
