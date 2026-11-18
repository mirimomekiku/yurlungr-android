package dev.yurlungr.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.launch

/** Where the layout changes on tablets, unfolded foldables and Chromebook windows. */
object WindowLayout {
    /** Material's "expanded" width class: the sidebar stays open beside the screen. */
    const val PINNED_SIDEBAR_DP = 840
    const val SIDEBAR_DP = 300
    /** Room left for the screen that's enough for two panes side by side. */
    const val TWO_PANE_DP = 720

    fun pinnedSidebar(windowDp: Int): Boolean = windowDp >= PINNED_SIDEBAR_DP

    /** Two panes when what's left after the (shown) sidebar is wide enough. */
    fun twoPane(windowDp: Int, sidebarShown: Boolean): Boolean =
        windowDp - (if (pinnedSidebar(windowDp) && sidebarShown) SIDEBAR_DP else 0) >= TWO_PANE_DP
}

/** True when the current screen has room for two panes (e.g. a project beside the file it opened). */
val LocalTwoPane = compositionLocalOf { false }

/**
 * The sidebar as a swipe-in drawer on phones and pinned beside the screen on wide windows; ☰ hides
 * and shows the pinned one. [content] stays at the same place in the tree either way, so a
 * rotation or fold change keeps every screen's state. [content] gets the ☰ action to provide.
 */
@Composable
fun AdaptiveShell(
    drawerState: DrawerState,
    gesturesEnabled: Boolean,
    drawer: @Composable (pinned: Boolean) -> Unit,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val widthPx = LocalWindowInfo.current.containerSize.width
    val windowDp = with(LocalDensity.current) { widthPx.toDp().value.toInt() }
    val pinned = WindowLayout.pinnedSidebar(windowDp)
    var sidebarShown by rememberSaveable { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    // Unfolding with the drawer open: the pinned sidebar takes over.
    LaunchedEffect(pinned) { if (pinned && drawerState.currentValue != DrawerValue.Closed) drawerState.snapTo(DrawerValue.Closed) }
    val openDrawer: () -> Unit = remember(pinned) {
        if (pinned) ({ sidebarShown = !sidebarShown }) else ({ scope.launch { drawerState.open() } })
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !pinned && gesturesEnabled,
        drawerContent = { if (!pinned) drawer(false) },
    ) {
        Row(Modifier.fillMaxSize()) {
            if (pinned && sidebarShown) drawer(true)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(LocalTwoPane provides WindowLayout.twoPane(windowDp, sidebarShown)) {
                    content(openDrawer)
                }
            }
        }
    }
}
