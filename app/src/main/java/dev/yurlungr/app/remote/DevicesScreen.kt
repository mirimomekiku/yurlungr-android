package dev.yurlungr.app.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.DeviceIcons
import dev.yurlungr.app.ui.Dot
import dev.yurlungr.app.ui.Group
import dev.yurlungr.app.ui.GroupRow
import dev.yurlungr.app.ui.Keyboards
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.SectionLabel
import kotlin.math.cos
import kotlin.math.sin

/** Something drawn on the map around this phone. */
private sealed interface Node {
    val key: String
    val name: String

    /** A phone using this one (connected to our server). */
    data class Client(val device: RemoteServer.ConnectedDevice) : Node {
        override val key get() = "c:${device.address}:${device.since}"
        override val name get() = device.name
    }

    /** A phone this one can use (saved host). */
    data class Host(val host: RemoteHost) : Node {
        override val key get() = "h:${host.id}"
        override val name get() = host.name
    }
}

/**
 * Devices as a connection map: this phone in the middle, phones using it and phones it can use
 * around it. Tap a node for its details; sharing controls and pairing sit below.
 */
@Composable
fun DevicesScreen(onBack: () -> Unit, onOpenHost: (RemoteHost) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val canHost = remember { RuntimePaths(context).isBootstrapInstalled }
    var adding by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    val tailscaleOn = remember(RemoteServer.status) { RemoteServer.tailscaleAddresses().isNotEmpty() }

    val nodes: List<Node> = RemoteServer.devices.map { Node.Client(it) } + RemoteHosts.hosts.map { Node.Host(it) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selected = nodes.firstOrNull { it.key == selectedKey } ?: nodes.firstOrNull { it is Node.Client }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenHeader("Devices", onBack) {
            TailscalePill(tailscaleOn, Modifier.padding(end = 8.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                ConnectionMap(
                    sharing = RemoteServer.status is RemoteServer.Status.Running,
                    canHost = canHost,
                    nodes = nodes,
                    selectedKey = selected?.key,
                    onSelect = { selectedKey = it.key },
                )
            }
            selected?.let { node -> item(key = "detail") { NodeCard(node, onOpenHost) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canHost) {
                        Button(
                            onClick = {
                                if (RemoteServer.status is RemoteServer.Status.Off) RemoteServer.enable(true)
                                pairing = true
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("Pair a device") }
                    }
                    FilledTonalButton(onClick = { adding = true }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Connect to one") }
                }
            }
            if (canHost) {
                item { SectionLabel("Share this phone") }
                item { ShareGroup() }
            }
            item {
                Text(
                    "Run agents on one phone and use them from another. Install Tailscale on both phones and sign in to the same account; traffic then stays inside your private network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }

    if (adding) {
        AddHostDialog(
            onDismiss = { adding = false },
            onAdd = { name, address, code ->
                adding = false
                onOpenHost(RemoteHosts.add(name, address, code))
            },
        )
    }
    if (pairing) PairDialog { pairing = false }
}

@Composable
private fun TailscalePill(on: Boolean, modifier: Modifier = Modifier) {
    val tones = AppColors.status
    Row(
        modifier.height(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(Ph.WifiHigh, null, size = 14.dp, tint = if (on) tones.success else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(6.dp))
        Text(if (on) "Tailscale on" else "Tailscale off", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Map ----------------------------------------------------------------------------------

@Composable
private fun ConnectionMap(sharing: Boolean, canHost: Boolean, nodes: List<Node>, selectedKey: String?, onSelect: (Node) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val idle = colors.outlineVariant
    val shown = nodes.take(5)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(330.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.12f), colors.surfaceContainerLow), radius = 700f)),
    ) {
        val w = maxWidth
        val center = Pair(w / 2, 138.dp)
        // Spread the other devices along an arc below this phone.
        val positions = shown.indices.map { i ->
            val n = shown.size
            val deg = if (n == 1) 120.0 else 150.0 - (120.0 * i / (n - 1))
            val rad = Math.toRadians(deg)
            val rx = (w.value / 2 - 52)
            Pair(center.first + (rx * cos(rad)).dp, center.second + (118 * sin(rad)).dp)
        }
        val density = LocalDensity.current
        val motion = rememberInfiniteTransition(label = "map")
        // Dashes flow from the other phone toward this one while it's connected.
        val flow by motion.animateFloat(0f, -60f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "flow")
        // Sonar-style rings while sharing: "this phone is listening".
        val pulse by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "pulse")
        // The rings slowly breathe so the map never looks frozen.
        val breathe by motion.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
        Canvas(Modifier.fillMaxSize()) {
            val c = with(density) { Offset(center.first.toPx(), center.second.toPx()) }
            drawCircle(idle.copy(alpha = 0.5f), radius = 70.dp.toPx() * breathe, center = c, style = Stroke(1.dp.toPx()))
            drawCircle(idle.copy(alpha = 0.3f), radius = 118.dp.toPx() * (2f - breathe), center = c, style = Stroke(1.dp.toPx()))
            if (sharing) {
                for (k in 0..1) {
                    val t = (pulse + k * 0.5f) % 1f
                    drawCircle(
                        accent.copy(alpha = 0.45f * (1f - t)),
                        radius = 40.dp.toPx() + t * 100.dp.toPx(),
                        center = c,
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
            shown.forEachIndexed { i, node ->
                val p = with(density) { Offset(positions[i].first.toPx(), positions[i].second.toPx()) }
                val live = node is Node.Client
                drawLine(
                    color = if (live) accent else idle,
                    start = p,
                    end = c,
                    strokeWidth = (if (live) 2.5f else 2f).dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(if (live) floatArrayOf(14f, 16f) else floatArrayOf(5f, 18f), if (live) flow else 0f),
                )
            }
        }
        MapNode(
            title = "This phone",
            subtitle = when {
                !canHost -> "client only"
                sharing -> "sharing"
                else -> "not sharing"
            },
            size = 76.dp,
            center = center,
            filled = true,
            dim = false,
            live = sharing,
            onClick = null,
        )
        shown.forEachIndexed { i, node ->
            MapNode(
                title = node.name,
                subtitle = when (node) {
                    is Node.Client -> "using this phone"
                    is Node.Host -> "tap to use"
                },
                size = 54.dp,
                center = positions[i],
                filled = false,
                dim = node is Node.Host,
                selected = node.key == selectedKey,
                onClick = { onSelect(node) },
            )
        }
        if (shown.isEmpty()) {
            Text(
                "No other devices yet",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun MapNode(
    title: String,
    subtitle: String,
    size: Dp,
    center: Pair<Dp, Dp>,
    filled: Boolean,
    dim: Boolean,
    selected: Boolean = false,
    /** Subtitle in green (live) rather than grey. */
    live: Boolean = !dim,
    onClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val labelWidth = 104.dp
    // Nodes pop in when they appear (a phone connects, the screen opens).
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow)) }
    val ring by animateColorAsState(
        if (selected) colors.primary else if (dim) colors.outlineVariant else colors.primary.copy(alpha = 0.6f),
        tween(250),
        label = "ring",
    )
    Column(
        Modifier
            .offset(x = center.first - labelWidth / 2, y = center.second - size / 2)
            .graphicsLayer {
                scaleX = 0.6f + 0.4f * appear.value
                scaleY = 0.6f + 0.4f * appear.value
                alpha = appear.value.coerceIn(0f, 1f)
            }
            .width(labelWidth)
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val shape = RoundedCornerShape(size * 0.34f)
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(if (filled) colors.primary else colors.surfaceContainerHigh)
                .border(if (selected) 2.dp else 1.dp, ring, shape),
            contentAlignment = Alignment.Center,
        ) {
            PhIcon(deviceIcon(title), null, size = size * 0.46f, tint = if (filled) colors.onPrimary else if (dim) colors.onSurfaceVariant else colors.onSurface)
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = if (live) AppColors.status.success else colors.onSurfaceVariant, maxLines = 1)
    }
}

/** A laptop, tablet or phone glyph, guessed from the device's name. */
private fun deviceIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        listOf("laptop", "book", "desktop", "pc", "mac", "fedora", "ubuntu", "linux", "windows").any { it in n } -> DeviceIcons.Laptop
        listOf("tablet", "tab ", "pad", "sm-x", "sm-t").any { it in n } -> DeviceIcons.Tablet
        else -> DeviceIcons.Phone
    }
}

// ---- Details ------------------------------------------------------------------------------

@Composable
private fun NodeCard(node: Node, onOpenHost: (RemoteHost) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(colors.surfaceContainer)
            .animateContentSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (node) {
            is Node.Client -> {
                val d = node.device
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(AppColors.status.success)
                    Spacer(Modifier.width(10.dp))
                    Text(d.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "connected " + DateUtils.getRelativeTimeSpanString(d.since, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("${d.openChats}", "chats open", Modifier.weight(1f))
                    Stat("${ChatSessions.chats.count { it.isRunning }}", "agents working", Modifier.weight(1f))
                    Stat(d.address.ifEmpty { "—" }, "address", Modifier.weight(1.3f), mono = true)
                }
                OutlinedButton(onClick = { RemoteServer.disconnect(d) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect ${d.name}", color = colors.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            is Node.Host -> {
                val h = node.host
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(colors.outline)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(h.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpenHost(h) }, modifier = Modifier.weight(1f)) { Text("Open chats") }
                    OutlinedButton(onClick = { RemoteHosts.remove(h) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp),
    ) {
        Text(
            value,
            fontSize = if (mono) 12.sp else 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = if (mono) FontFamily.Monospace else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(24.dp).padding(top = if (mono) 4.dp else 0.dp),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

// ---- Sharing ------------------------------------------------------------------------------

@Composable
private fun ShareGroup() {
    val context = LocalContext.current
    val status = RemoteServer.status
    val enabled = status !is RemoteServer.Status.Off
    Group {
        GroupRow(
            "Let my devices use this phone",
            subtitle = "Agents, projects and builds stay on ${Build.MODEL}",
            trailing = { Switch(checked = enabled, onCheckedChange = { RemoteServer.enable(it) }) },
        )
        when (status) {
            is RemoteServer.Status.Running -> {
                val address = status.addresses.firstOrNull()
                GroupRow(
                    "Address",
                    subtitle = status.addresses.joinToString(" · ").ifEmpty { "No Tailscale or Wi-Fi address" },
                    trailing = { if (address != null) TextButton(onClick = { copyText(context, address) }) { Text("Copy") } },
                )
                GroupRow(
                    "Pairing code",
                    subtitle = RemoteServer.code,
                    trailing = {
                        TextButton(onClick = { RemoteServer.resetCode() }) { Text("New") }
                        TextButton(onClick = { copyText(context, RemoteServer.code) }) { Text("Copy") }
                    },
                )
            }
            is RemoteServer.Status.Failed -> GroupRow(
                "Sharing stopped",
                subtitle = status.message,
                subtitleColor = MaterialTheme.colorScheme.error,
                trailing = { TextButton(onClick = { RemoteServer.start() }) { Text("Retry") } },
            )
            RemoteServer.Status.Off -> Unit
        }
        GroupRow(
            "Also allow local Wi-Fi",
            subtitle = "Without Tailscale, on private networks only. Traffic is encrypted and checked with the pairing code.",
            trailing = { Switch(checked = RemoteServer.allowLan, onCheckedChange = { RemoteServer.allowLocalWifi(it) }) },
        )
        if (context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn") == null) {
            GroupRow(
                "Tailscale isn't installed",
                subtitle = "Get it from the Play Store, then sign in on both phones.",
                icon = Ph.Info,
                iconTint = AppColors.status.warning,
                trailing = {},
            )
        }
    }
}

/** The code and address in large type, to read off on the other phone. */
@Composable
private fun PairDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val status = RemoteServer.status
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair a device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("On the other phone: Yurlungr → Devices → Connect to one, then enter:", style = MaterialTheme.typography.bodyMedium)
                when (status) {
                    is RemoteServer.Status.Running -> {
                        BigField("Address", status.addresses.firstOrNull() ?: "—")
                        BigField("Pairing code", RemoteServer.code)
                    }
                    is RemoteServer.Status.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
                    RemoteServer.Status.Off -> Text("Starting…")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val running = status as? RemoteServer.Status.Running
                copyText(context, listOfNotNull(running?.addresses?.firstOrNull(), RemoteServer.code).joinToString("\n"))
            }) { Text("Copy both") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun BigField(label: String, value: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

@Composable
private fun AddHostDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to a device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "On the other phone: Devices → Pair a device. Enter what it shows.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("Address") }, placeholder = { Text("100.x.y.z") }, singleLine = true, keyboardOptions = Keyboards.Url)
                OutlinedTextField(code, { code = it }, label = { Text("Pairing code") }, singleLine = true, keyboardOptions = Keyboards.Code)
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(name, address, code) }, enabled = address.isNotBlank() && code.isNotBlank()) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("yurlungr", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
