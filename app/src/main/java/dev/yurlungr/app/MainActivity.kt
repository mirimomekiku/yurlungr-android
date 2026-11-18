package dev.yurlungr.app

import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import dev.yurlungr.app.ui.SkeletonChat
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.chat.ChatScreen
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.chat.NewChatScreen
import dev.yurlungr.app.home.AgentsScreen
import dev.yurlungr.app.editor.EditorScreen
import dev.yurlungr.app.projects.LastProject
import dev.yurlungr.app.projects.ProjectScreen
import dev.yurlungr.app.projects.ProjectsScreen
import dev.yurlungr.app.remote.DevicesScreen
import dev.yurlungr.app.remote.RemoteConnections
import dev.yurlungr.app.remote.RemoteHomeScreen
import dev.yurlungr.app.remote.RemoteHosts
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.setup.SettingsScreen
import dev.yurlungr.app.setup.SetupScreen
import dev.yurlungr.app.setup.SetupViewModel
import dev.yurlungr.app.terminal.TerminalScreen
import dev.yurlungr.app.terminal.TerminalSessions
import androidx.compose.animation.AnimatedContent
import dev.yurlungr.app.ui.AppDrawer
import dev.yurlungr.app.ui.NavDirection
import dev.yurlungr.app.ui.screenTransition
import dev.yurlungr.app.ui.AppTheme
import dev.yurlungr.app.ui.LocalOpenDrawer
import dev.yurlungr.app.ui.LocalTopLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File

sealed interface Route {
    data object NewChat : Route
    data class Chat(val id: String) : Route
    data object Setup : Route
    data object Settings : Route
    data object DevEnvironment : Route
    /** Web apps, listening ports and processes. */
    data object Servers : Route
    data class Preview(val url: String, val title: String) : Route
    data class ProjectAgents(val path: String) : Route
    data object Tour : Route
    data class Merge(val path: String) : Route
    data object Terminal : Route
    data object Agents : Route
    data object Activity : Route
    data class Extensions(val agent: String?, val project: String?) : Route
    data object Devices : Route
    data class RemoteHome(val hostId: String) : Route
    data class RemoteChat(val hostId: String, val chatId: String) : Route
    data object Projects : Route
    data class Project(val path: String, val tab: String? = null) : Route
    data class FileView(val path: String, val edit: Boolean = false, val line: Int? = null) : Route
}

/**
 * Navigation like Gemini/ChatGPT: the sidebar switches between top-level places (each replaces
 * the stack); drilling in (a project, a file) pushes. Back at a top-level place returns to the
 * new-chat screen, and back there leaves the app.
 */
class Navigator(start: Route) {
    private val stack = mutableStateListOf(start)
    val current: Route get() = stack.last()
    val isTopLevel: Boolean get() = stack.size == 1
    val canGoBack: Boolean get() = stack.size > 1 || current != Route.NewChat

    /** How the last navigation moved, so the transition can slide the right way. */
    var direction by mutableStateOf(NavDirection.Replace)
        private set

    fun push(route: Route) {
        if (current == route) return
        direction = NavDirection.Push
        stack += route
    }

    /** The project a stack of opened files sits on (for the side-by-side layout), if any. */
    fun projectBelow(): Route.Project? {
        for (i in stack.lastIndex - 1 downTo 0) {
            when (val r = stack[i]) {
                is Route.Project -> return r
                is Route.FileView -> continue
                else -> return null
            }
        }
        return null
    }

    fun pop() {
        direction = NavDirection.Pop
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else stack[0] = Route.NewChat
    }

    /** Shows [route] in place of the current one (switching editor tabs). */
    fun replace(route: Route) {
        if (current == route) return
        direction = NavDirection.Replace
        stack[stack.lastIndex] = route
    }

    fun reset(route: Route) {
        if (stack.size == 1 && current == route) return
        direction = NavDirection.Replace
        stack.clear()
        stack += route
    }
}

class MainActivity : ComponentActivity() {
    private val setupViewModel: SetupViewModel by viewModels()
    private var resumeTick by mutableIntStateOf(0)
    private var pendingChatId by mutableStateOf<String?>(null)
    private var pendingScreen by mutableStateOf<Route?>(null)
    private var showPalette by mutableStateOf(false)
    /** Something shared from another app, waiting for the share sheet. */
    private var pendingShare by mutableStateOf<dev.yurlungr.app.ui.SharedContent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingChatId = intent?.getStringExtra(EXTRA_CHAT_ID)
        pendingScreen = debugScreen(intent) ?: terminalFrom(intent) ?: openFrom(intent)
        pendingShare = dev.yurlungr.app.ui.SharedContent.from(intent, packageName)
        val nav = Navigator(if (RuntimePaths(this).isBootstrapInstalled) Route.NewChat else Route.Setup)

        setContent {
            // Status and navigation bar icons follow the app's theme, not the phone's.
            val dark = dev.yurlungr.app.ui.ThemePrefs.isDark()
            androidx.compose.runtime.DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                        android.graphics.Color.argb(0xe6, 0xff, 0xff, 0xff),
                        android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b),
                    ) { dark },
                )
                onDispose {}
            }
            AppTheme {
                val context = LocalContext.current
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                // Opening the sidebar (button or swipe) puts the keyboard away.
                val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                val focus = androidx.compose.ui.platform.LocalFocusManager.current
                LaunchedEffect(drawer.targetValue) {
                    if (drawer.targetValue == DrawerValue.Open) {
                        keyboard?.hide()
                        focus.clearFocus()
                    }
                }
                fun go(route: Route) {
                    scope.launch { drawer.close() }
                    // "Projects" reopens the last project's workspace; its name menu lists the rest.
                    val target = if (route == Route.Projects) LastProject.get(context)?.let { Route.Project(it.path) } ?: route else route
                    nav.reset(target)
                }

                // The tour, once, the first time the app opens with the Linux environment ready.
                LaunchedEffect(Unit) {
                    if (RuntimePaths(context).isBootstrapInstalled && !dev.yurlungr.app.ui.TourPrefs.seen(context) && pendingScreen == null && pendingShare == null) {
                        nav.push(Route.Tour)
                    }
                }
                LaunchedEffect(pendingScreen) {
                    pendingScreen?.let(nav::reset)
                    pendingScreen = null
                }

                LaunchedEffect(pendingChatId) {
                    if (pendingChatId == null) return@LaunchedEffect
                    // Saved chats load in the background at startup; a notification tap can come first.
                    androidx.compose.runtime.snapshotFlow { ChatSessions.loaded }.first { it }
                    pendingChatId?.let { id -> if (ChatSessions.get(id) != null) nav.reset(Route.Chat(id)) }
                    pendingChatId = null
                }

                ModalNavigationDrawer(
                    drawerState = drawer,
                    // The terminal needs horizontal drags for itself (selection, scrolling).
                    gesturesEnabled = drawer.isOpen || nav.current != Route.Terminal,
                    drawerContent = {
                        AppDrawer(
                            current = nav.current,
                            onNewChat = { go(Route.NewChat) },
                            onOpenChat = { go(Route.Chat(it.id)) },
                            onDeleteChat = { chat ->
                                if (nav.current == Route.Chat(chat.id)) nav.reset(Route.NewChat)
                                chat.delete()
                            },
                            onNavigate = ::go,
                            onSearch = { scope.launch { drawer.close() }; showPalette = true },
                        )
                    },
                ) {
                    // Top-level places without their own back handling return to the new-chat screen.
                    BackHandler(enabled = nav.canGoBack) { nav.pop() }
                    Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        dev.yurlungr.app.ui.LocalOpenFile provides { nav.push(Route.FileView(it.path)) },
                        LocalOpenDrawer provides { scope.launch { drawer.open() } },
                        dev.yurlungr.app.ui.LocalOpenExtensions provides { agent, project -> nav.push(Route.Extensions(agent, project)) },
                        LocalTopLevel provides nav.isTopLevel,
                        dev.yurlungr.app.ui.LocalOpenPalette provides { showPalette = true },
                    ) {
                        AnimatedContent(
                            targetState = nav.current,
                            transitionSpec = { screenTransition(nav.direction) },
                            label = "screen",
                        ) { route ->
                            // Tablets and unfolded foldables: the project beside the file it opened.
                            val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width
                            val wide = with(androidx.compose.ui.platform.LocalDensity.current) { window.toDp() } >= 840.dp
                            val project = if (wide && route is Route.FileView) nav.projectBelow() else null
                            if (project != null) {
                                androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                                    Box(Modifier.weight(0.42f)) { Screen(project, nav, context) }
                                    androidx.compose.material3.VerticalDivider(color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)
                                    Box(Modifier.weight(0.58f)) { Screen(route, nav, context) }
                                }
                            } else Screen(route, nav, context)
                        }
                    }
                    dev.yurlungr.app.chat.UrgentBanner(
                        viewing = (nav.current as? Route.Chat)?.id,
                        onOpen = { nav.reset(Route.Chat(it)) },
                        modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding(),
                    )
                    }
                    pendingRun?.let { (command, name, reason) ->
                        dev.yurlungr.app.ui.AppSheet(
                            "Run $name now?", onDismiss = { pendingRun = null }, subtitle = reason, icon = dev.yurlungr.app.ui.Ph.Lightning,
                            actions = {
                                androidx.compose.material3.OutlinedButton(onClick = { pendingRun = null }, modifier = Modifier.weight(1f)) { Text("Later") }
                                androidx.compose.material3.Button(onClick = {
                                    pendingRun = null
                                    runInTerminal(context, nav, command, name, confirmed = true)
                                }, modifier = Modifier.weight(1f)) { Text("Run anyway") }
                            },
                        ) {
                            Text("Plugging in, or letting the phone cool down, keeps builds fast. Turn these warnings off in Settings → Feedback.",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    pendingShare?.let { shared ->
                        dev.yurlungr.app.ui.ShareSheet(shared, onDismiss = { pendingShare = null }, onOpenChat = { nav.reset(Route.Chat(it.id)) })
                    }
                    if (showPalette) {
                        fun place(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, route: Route) =
                            dev.yurlungr.app.ui.PaletteItem("Go to", title, null, icon) { if (route == Route.NewChat || route == Route.Projects || route == Route.Terminal || route == Route.Servers || route == Route.Agents || route == Route.Devices || route == Route.Settings) go(route) else nav.push(route) }
                        dev.yurlungr.app.ui.CommandPalette(
                            actions = listOf(
                                place("New chat", dev.yurlungr.app.ui.Ph.NotePencil, Route.NewChat),
                                place("Projects", dev.yurlungr.app.ui.Ph.FolderSimple, Route.Projects),
                                place("Terminal", dev.yurlungr.app.ui.Ph.TerminalWindow, Route.Terminal),
                                place("Servers", dev.yurlungr.app.ui.Ph.Globe, Route.Servers),
                                place("Agents", dev.yurlungr.app.ui.Ph.Sparkle, Route.Agents),
                                place("Devices", dev.yurlungr.app.ui.Ph.Devices, Route.Devices),
                                place("Settings", dev.yurlungr.app.ui.Ph.GearSix, Route.Settings),
                                place("System checks", dev.yurlungr.app.ui.Ph.ListChecks, Route.Setup),
                                place("Development environment", dev.yurlungr.app.ui.Ph.Wrench, Route.DevEnvironment),
                            ),
                            onOpenProject = { nav.reset(Route.Project(it.path)) },
                            onOpenFile = { nav.push(Route.FileView(it.path)) },
                            onOpenChat = { nav.reset(Route.Chat(it)) },
                            runInTerminal = { command, name -> runInTerminal(context, nav, command, name) },
                            onDismiss = { showPalette = false },
                        )
                    }
                    // Registered last, so it wins: back closes the sidebar first.
                    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
                }
            }
        }
    }

    @Composable
    private fun Screen(route: Route, nav: Navigator, context: Context) {
        val back = { nav.pop() }
        when (route) {
            Route.NewChat -> NewChatScreen(
                refreshTick = resumeTick,
                onStarted = { nav.reset(Route.Chat(it.id)) },
                onLogin = { openLogin(context, it, nav) },
                onInstallAgents = { openAgentSetup(context, nav) },
            )
            is Route.Chat -> {
                val chat = ChatSessions.get(route.id)
                if (chat == null) {
                    // Not loaded yet (startup) isn't gone: wait for the saved chats before giving up on it.
                    if (ChatSessions.loaded) LaunchedEffect(route) { nav.reset(Route.NewChat) }
                } else {
                    ChatScreen(
                        chat,
                        onBack = back,
                        onLogin = { openLogin(context, it, nav) },
                        onNewChat = { nav.reset(Route.NewChat) },
                        onInstallAgents = { openAgentSetup(context, nav) },
                        onSystemChecks = { nav.push(Route.Setup) },
                        onOpenChat = { nav.reset(Route.Chat(it)) },
                    )
                }
            }
            is Route.Extensions -> dev.yurlungr.app.agents.AgentExtensionsScreen(
                route.agent, route.project, onBack = back,
                onOpenFile = { nav.push(Route.FileView(it.path, edit = true)) },
                onRun = { command, title -> runInTerminal(context, nav, command, title) },
            )
            Route.Activity -> dev.yurlungr.app.chat.ActivityScreen(onBack = back, onOpenChat = { nav.reset(Route.Chat(it)) })
            Route.Agents -> AgentsScreen(
                refreshTick = resumeTick,
                onBack = back,
                onOpenChat = { nav.reset(Route.Chat(it.id)) },
                onLogin = { openLogin(context, it, nav) },
                onInstallAgents = { openAgentSetup(context, nav) },
                onInstallAgent = { agent ->
                    agent.installCommand()?.let { runInTerminal(context, nav, it, "Install ${agent.displayName}") }
                },
            )
            Route.Setup -> SetupScreen(
                setupViewModel,
                firstRun = nav.isTopLevel,
                onOpenTerminal = { nav.push(Route.Terminal) },
                onInstallAgents = { openAgentSetup(context, nav) },
                onInstallAndroidSdk = {
                    runInTerminal(context, nav, dev.yurlungr.app.runtime.setupScriptCommand("android-sdk-setup.sh", test = true), "Android SDK")
                },
                onInstallDevTools = { openDevToolsSetup(context, nav) },
                onInstallSetup = { pack ->
                    runInTerminal(context, nav, dev.yurlungr.app.runtime.setupScriptCommand(requireNotNull(pack.script)), pack.title)
                },
                // Opened from Settings → back there; as the first-run screen → home.
                onHome = { if (nav.isTopLevel) nav.reset(Route.NewChat) else nav.pop() },
                onDevices = { nav.push(Route.Devices) },
                onDevEnvironment = { nav.push(Route.DevEnvironment) },
                onUpdatePackages = { runInTerminal(context, nav, dev.yurlungr.app.runtime.packagesUpgradeCommand(), "Update packages") },
            )
            Route.Settings -> SettingsScreen(
                setupViewModel,
                refreshTick = resumeTick,
                onBack = back,
                onSystemChecks = { nav.push(Route.Setup) },
                onAgents = { nav.reset(Route.Agents) },
                runInTerminal = { command, name -> runInTerminal(context, nav, command, name) },
                onDevEnvironment = { nav.push(Route.DevEnvironment) },
                onTour = { nav.push(Route.Tour) },
            )
            Route.DevEnvironment -> dev.yurlungr.app.setup.DevEnvironmentScreen(
                refreshTick = resumeTick,
                onBack = back,
                onSystemChecks = { nav.push(Route.Setup) },
                onInstallSetup = { pack ->
                    runInTerminal(context, nav, dev.yurlungr.app.runtime.setupScriptCommand(requireNotNull(pack.script)), pack.title)
                },
                runInTerminal = { command, name -> runInTerminal(context, nav, command, name) },
            )
            Route.Terminal -> TerminalScreen(onBack = {
                nav.pop()
                setupViewModel.refresh()
                resumeTick++
            })
            Route.Devices -> DevicesScreen(onBack = back, onOpenHost = { nav.push(Route.RemoteHome(it.id)) })
            is Route.RemoteHome -> {
                val host = RemoteHosts.get(route.hostId)
                if (host == null) {
                    LaunchedEffect(route) { nav.pop() }
                } else {
                    RemoteHomeScreen(host, onBack = back, onOpenChat = { nav.push(Route.RemoteChat(host.id, it)) })
                }
            }
            is Route.RemoteChat -> RemoteChatRoute(route, onBack = back)
            Route.Projects -> ProjectsScreen(
                refreshTick = resumeTick,
                onBack = back,
                onOpenProject = { nav.reset(Route.Project(it.path)) },
                runInTerminal = { command, name -> runInTerminal(context, nav, command, name) },
            )
            is Route.Project -> ProjectScreen(
                dir = File(route.path),
                initialTab = route.tab,
                refreshTick = resumeTick,
                onBack = back,
                onOpenFile = { nav.push(Route.FileView(it.path)) },
                onNewChat = { agent -> nav.reset(Route.Chat(ChatSessions.create(context, agent, File(route.path)).id)) },
                onOpenChat = { nav.reset(Route.Chat(it.id)) },
                onSwitchProject = { nav.reset(Route.Project(it.path)) },
                onAllProjects = { nav.push(Route.Projects) },
                runInTerminal = { command, name -> runInTerminal(context, nav, command, name) },
                onOpenTerminal = { nav.push(Route.Terminal) },
                onPreview = { url, title -> nav.push(Route.Preview(url, title)) },
                onAgentSettings = { nav.push(Route.ProjectAgents(route.path)) },
                onOpenFileAt = { f, line -> nav.push(Route.FileView(f.path, edit = line != null, line = line)) },
                onResolveConflict = { nav.push(Route.Merge(it.path)) },
            )
            Route.Servers -> dev.yurlungr.app.web.ServersScreen(
                onBack = back,
                onPreview = { url, title -> nav.push(Route.Preview(url, title)) },
                onOpenTerminal = { nav.push(Route.Terminal) },
            )
            is Route.Preview -> dev.yurlungr.app.web.PreviewScreen(route.url, route.title, onBack = back)
            is Route.Merge -> dev.yurlungr.app.editor.MergeScreen(File(route.path), onBack = back,
                onOpenEditor = { nav.replace(Route.FileView(route.path, edit = true)) })
            Route.Tour -> dev.yurlungr.app.ui.TourScreen(
                onDone = back,
                onSample = { dir ->
                    dev.yurlungr.app.projects.LastProject.set(context, dir)
                    nav.reset(Route.Project(dir.path))
                },
            )
            is Route.ProjectAgents -> dev.yurlungr.app.projects.ProjectAgentsScreen(
                File(route.path),
                onBack = back,
                onOpenFile = { nav.push(Route.FileView(it.path)) },
            )
            is Route.FileView -> {
                val file = File(route.path)
                if (dev.yurlungr.app.editor.Sqlite.isDatabase(file)) {
                    dev.yurlungr.app.editor.DatabaseScreen(file, onBack = back)
                } else if (!route.edit && route.line == null && dev.yurlungr.app.editor.FilePreview.kindOf(file.name).let { it != dev.yurlungr.app.editor.PreviewKind.None && it != dev.yurlungr.app.editor.PreviewKind.Json }) {
                    // Pictures, HTML/SVG pages and CSV/TSV tables open as a preview; Edit (where it makes sense) opens the text.
                    val image = dev.yurlungr.app.editor.FilePreview.kindOf(file.name) == dev.yurlungr.app.editor.PreviewKind.Image
                    dev.yurlungr.app.editor.FilePreviewScreen(file, onBack = back,
                        onEdit = if (image) null else ({ nav.push(Route.FileView(route.path, edit = true)) }))
                } else if (!route.edit && route.line == null && dev.yurlungr.app.editor.isMarkdownFile(file.name)) {
                    dev.yurlungr.app.editor.MarkdownScreen(file, resumeTick, onBack = back,
                        onEdit = { nav.push(Route.FileView(route.path, edit = true)) })
                } else {
                    EditorScreen(file, onBack = back, line = route.line, onOpenAt = { f, l -> nav.push(Route.FileView(f.path, edit = true, line = l)) },
                        onSwitch = { nav.replace(Route.FileView(it.path, edit = true)) },
                        onResolveConflicts = { nav.replace(Route.Merge(it.path)) })
                }
            }
        }
    }

    @Composable
    private fun RemoteChatRoute(route: Route.RemoteChat, onBack: () -> Unit) {
        val host = RemoteHosts.get(route.hostId)
        if (host == null) {
            LaunchedEffect(route) { onBack() }
            return
        }
        val conn = remember(host.id) { RemoteConnections.get(host) }
        // The chat list arrives after connecting; look the chat up once it's there.
        val chat = remember(route.chatId, conn.status, conn.chats.size) { conn.chat(route.chatId) }
        if (chat == null) {
            SkeletonChat(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp))
        } else {
            ChatScreen(chat, onBack = onBack, onLogin = {})
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CHAT_ID)?.let { pendingChatId = it }
        debugScreen(intent)?.let { pendingScreen = it }
        terminalFrom(intent)?.let { pendingScreen = it }
        openFrom(intent)?.let { pendingScreen = it }
        dev.yurlungr.app.ui.SharedContent.from(intent, packageName)?.let { pendingShare = it }
    }

    /**
     * Debug builds only: `adb shell am start -n com.termux/dev.yurlungr.app.MainActivity --es screen devices`
     * opens a screen directly, for checking layouts without tapping through the app.
     */
    /** Quick Settings tiles and the home-screen widget: open a place. */
    private fun openFrom(intent: Intent?): Route? = when (val place = intent?.getStringExtra(EXTRA_OPEN)) {
        null -> null
        dev.yurlungr.app.widgets.OpenIn.SERVERS -> Route.Servers
        dev.yurlungr.app.widgets.OpenIn.TERMINAL -> Route.Terminal
        dev.yurlungr.app.widgets.OpenIn.NEW_CHAT -> Route.NewChat
        dev.yurlungr.app.widgets.OpenIn.QUICK_ASK -> { dev.yurlungr.app.widgets.QuickAsk.pending = true; Route.NewChat }
        // This activity is exported, so any app can send this: only folders under ~/projects, or the
        // last project (which shortcuts and the widget point at), never the runtime's own folders.
        else -> place.removePrefix(dev.yurlungr.app.widgets.OpenIn.PROJECT).takeIf { it.isNotEmpty() && File(it).isDirectory }
            ?.takeIf { dev.yurlungr.app.projects.LastProject.isOpenable(this, File(it)) }
            ?.let { Route.Project(it) } ?: Route.Projects
    }

    /** A "finished" notification: show that terminal. */
    private fun terminalFrom(intent: Intent?): Route? {
        val id = intent?.getStringExtra(EXTRA_TERMINAL_ID) ?: return null
        TerminalSessions.sessions.firstOrNull { TerminalSessions.statusId(it) == id }?.let(TerminalSessions::select) ?: return null
        return Route.Terminal
    }

    private fun debugScreen(intent: Intent?): Route? {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return when (intent?.getStringExtra("screen")) {
            "chat" -> Route.NewChat
            "projects" -> Route.Projects
            "terminal" -> Route.Terminal
            "agents" -> Route.Agents
            "devices" -> Route.Devices
            "settings" -> Route.Settings
            "checks" -> Route.Setup
            else -> intent?.getStringExtra("project")?.let { Route.Project(it, intent.getStringExtra("tab")) }
                ?: intent?.getStringExtra("file")?.let { Route.FileView(it) }
        }
    }

    override fun onStart() {
        super.onStart()
        ChatSessions.appVisible = true
    }

    override fun onStop() {
        ChatSessions.appVisible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Pick up changes made elsewhere (system settings, sign-ins done in the terminal).
        setupViewModel.refresh()
        resumeTick++
    }

    private fun openLogin(context: Context, agent: Agent, nav: Navigator) {
        val hint = agent.loginHint.replace("'", "'\\''")
        runInTerminal(
            context, nav,
            command = "printf '\\033[1;36m%s\\033[0m\\n\\n' '$hint'; ${agent.loginCommand}",
            name = "${agent.displayName} sign-in",
        )
    }

    private fun openAgentSetup(context: Context, nav: Navigator) =
        runInTerminal(context, nav, dev.yurlungr.app.runtime.setupScriptCommand("agents-setup.sh"), "Agent setup")

    private fun openDevToolsSetup(context: Context, nav: Navigator) =
        runInTerminal(context, nav, dev.yurlungr.app.runtime.setupScriptCommand("dev-tools-setup.sh"), "Developer tools")

    /** A long job waiting for the battery/heat confirmation: command, name, and why. */
    private var pendingRun by mutableStateOf<Triple<String, String, String>?>(null)

    private fun runInTerminal(context: Context, nav: Navigator, command: String, name: String, confirmed: Boolean = false) {
        val ui = dev.yurlungr.app.ui.Feedback
        if (!confirmed && ui.powerWarnings && ui.isHeavy(command)) {
            ui.powerConcern(ui.power(context))?.let { reason -> pendingRun = Triple(command, name, reason); return }
        }
        lifecycleScope.launch {
            runCatching {
                val paths = RuntimePaths(context)
                check(paths.isBootstrapInstalled) { "Install the Linux environment in System checks first." }
                // Also repairs scripts left by an older APK. Off the main thread: it's synchronized
                // with the launch-time refresh and may have to wait for it.
                withContext(kotlinx.coroutines.Dispatchers.IO) { dev.yurlungr.app.runtime.RuntimeAssets.install(context, paths) }
                TerminalSessions.create(context, command = command, name = name)
            }.onSuccess {
                nav.push(Route.Terminal)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                android.util.Log.e("MainActivity", "Cannot start $name", it)
                android.widget.Toast.makeText(context, "Couldn't start $name: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_TERMINAL_ID = "terminal_id"
        const val EXTRA_OPEN = "open"
    }
}
