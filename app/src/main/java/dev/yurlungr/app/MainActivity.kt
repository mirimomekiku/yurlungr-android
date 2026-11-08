package dev.yurlungr.app

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
import kotlinx.coroutines.launch
import java.io.File

sealed interface Route {
    data object NewChat : Route
    data class Chat(val id: String) : Route
    data object Setup : Route
    data object Settings : Route
    data object Terminal : Route
    data object Agents : Route
    data object Devices : Route
    data class RemoteHome(val hostId: String) : Route
    data class RemoteChat(val hostId: String, val chatId: String) : Route
    data object Projects : Route
    data class Project(val path: String, val tab: String? = null) : Route
    data class FileView(val path: String, val edit: Boolean = false) : Route
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

    fun pop() {
        direction = NavDirection.Pop
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else stack[0] = Route.NewChat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingChatId = intent?.getStringExtra(EXTRA_CHAT_ID)
        pendingScreen = debugScreen(intent)
        val nav = Navigator(if (RuntimePaths(this).isBootstrapInstalled) Route.NewChat else Route.Setup)

        setContent {
            AppTheme {
                val context = LocalContext.current
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                fun go(route: Route) {
                    scope.launch { drawer.close() }
                    // "Projects" reopens the last project's workspace; its name menu lists the rest.
                    val target = if (route == Route.Projects) LastProject.get(context)?.let { Route.Project(it.path) } ?: route else route
                    nav.reset(target)
                }

                LaunchedEffect(pendingScreen) {
                    pendingScreen?.let(nav::reset)
                    pendingScreen = null
                }

                LaunchedEffect(pendingChatId) {
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
                        )
                    },
                ) {
                    // Top-level places without their own back handling return to the new-chat screen.
                    BackHandler(enabled = nav.canGoBack) { nav.pop() }
                    CompositionLocalProvider(
                        dev.yurlungr.app.ui.LocalOpenFile provides { nav.push(Route.FileView(it.path)) },
                        LocalOpenDrawer provides { scope.launch { drawer.open() } },
                        LocalTopLevel provides nav.isTopLevel,
                    ) {
                        AnimatedContent(
                            targetState = nav.current,
                            transitionSpec = { screenTransition(nav.direction) },
                            label = "screen",
                        ) { route ->
                            Screen(route, nav, context)
                        }
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
                    LaunchedEffect(route) { nav.reset(Route.NewChat) }
                } else {
                    ChatScreen(
                        chat,
                        onBack = back,
                        onLogin = { openLogin(context, it, nav) },
                        onNewChat = { nav.reset(Route.NewChat) },
                    )
                }
            }
            Route.Agents -> AgentsScreen(
                refreshTick = resumeTick,
                onBack = back,
                onOpenChat = { nav.reset(Route.Chat(it.id)) },
                onLogin = { openLogin(context, it, nav) },
                onInstallAgents = { openAgentSetup(context, nav) },
            )
            Route.Setup -> SetupScreen(
                setupViewModel,
                firstRun = nav.isTopLevel,
                onOpenTerminal = { nav.push(Route.Terminal) },
                onInstallAgents = { openAgentSetup(context, nav) },
                onInstallAndroidSdk = {
                    runInTerminal(context, nav, "bash \"\$PREFIX/libexec/yurlungr/android-sdk-setup.sh\" --test", "Android SDK")
                },
                // Opened from Settings → back there; as the first-run screen → home.
                onHome = { if (nav.isTopLevel) nav.reset(Route.NewChat) else nav.pop() },
                onDevices = { nav.push(Route.Devices) },
            )
            Route.Settings -> SettingsScreen(
                setupViewModel,
                refreshTick = resumeTick,
                onBack = back,
                onSystemChecks = { nav.push(Route.Setup) },
                onAgents = { nav.reset(Route.Agents) },
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
            )
            is Route.FileView -> {
                val file = File(route.path)
                if (!route.edit && dev.yurlungr.app.editor.isMarkdownFile(file.name)) {
                    dev.yurlungr.app.editor.MarkdownScreen(file, resumeTick, onBack = back,
                        onEdit = { nav.push(Route.FileView(route.path, edit = true)) })
                } else {
                    EditorScreen(file, onBack = back)
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
    }

    /**
     * Debug builds only: `adb shell am start -n com.termux/dev.yurlungr.app.MainActivity --es screen devices`
     * opens a screen directly, for checking layouts without tapping through the app.
     */
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
        runInTerminal(context, nav, "bash \"\$PREFIX/libexec/yurlungr/agents-setup.sh\"", "Agent setup")

    private fun runInTerminal(context: Context, nav: Navigator, command: String, name: String) {
        TerminalSessions.create(context, command = command, name = name)
        nav.push(Route.Terminal)
    }

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
    }
}
