package dev.yurlungr.app.projects

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.Ph
import androidx.compose.animation.animateContentSize
import dev.yurlungr.app.ui.SkeletonLine
import dev.yurlungr.app.ui.SkeletonCard
import dev.yurlungr.app.ui.ScreenHeader
import android.text.format.DateUtils
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.SectionLabel
import dev.yurlungr.app.ui.StatusLabel
import dev.yurlungr.app.ui.WaveDots
import kotlinx.coroutines.launch
import dev.yurlungr.app.ui.Keyboards
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.projects.Git.Companion.shellQuote
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

private val PROJECT_NAME = Regex("[A-Za-z0-9._-]{1,64}")

/** Last fetched GitHub repositories, kept across visits so the list doesn't blink. */
private object RepoCache {
    var repos: List<GhRepo>? = null
}

private enum class Filter(val label: String) { All("All"), Local("On this phone"), Remote("Not cloned") }

/**
 * All projects: folders on this phone (with git status) and, when signed in to GitHub, the
 * account's repositories, each marked as on this phone or not cloned (Clone fetches it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    refreshTick: Int,
    onBack: () -> Unit,
    onOpenProject: (File) -> Unit,
    runInTerminal: (command: String, name: String) -> Unit,
    /** Opens a running web app in the in-app preview (after "install and run"). */
    onPreview: (url: String, title: String) -> Unit = { _, _ -> },
    onFreeSpace: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val git = remember { Git(paths) }
    val scope = rememberCoroutineScope()

    var projects by remember { mutableStateOf<List<File>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    val pullState = rememberPullToRefreshState()
    val statuses = remember { mutableStateMapOf<String, GitStatus>() }
    val origins = remember { mutableStateMapOf<String, String>() } // dir path -> github slug
    var gh by remember { mutableStateOf<GhStatus?>(null) }
    var repos by remember { mutableStateOf(RepoCache.repos) }
    var fetchingRepos by remember { mutableStateOf(false) }
    /** Why GitHub's list couldn't be refreshed (gh's own words), or null. */
    var repoError by remember { mutableStateOf<String?>(null) }
    var cloning by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var createBusy by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var query by rememberSaveable { mutableStateOf("") }
    val collapsed = remember { mutableStateListOf<String>() } // owners whose repo group is folded

    LaunchedEffect(refreshTick, reload) {
        loading = true
        refreshError = null
        repoError = null
        try {
            projects = withContext(Dispatchers.IO) {
                check(paths.projects.isDirectory || paths.projects.mkdirs()) { "Cannot open projects folder" }
                checkNotNull(paths.projects.listFiles { f -> f.isDirectory && !f.name.startsWith(".") })
                    .sortedByDescending { it.lastModified() }
            }
            loaded = true
            val currentPaths = projects.map { it.path }.toSet()
            statuses.keys.toList().filter { it !in currentPaths }.forEach { statuses.remove(it) }
            origins.keys.toList().filter { it !in currentPaths }.forEach { origins.remove(it) }
            coroutineScope {
                // GitHub in parallel with the local projects: a slow or offline network must not
                // keep the folders on this phone from showing their status.
                launch {
                    val status = git.ghStatus()
                    gh = status
                    when {
                        status.problem == GhProblem.Unreachable -> {
                            // Keep the saved list: being offline isn't being signed out.
                            repoError = status.detail ?: "GitHub can't be reached."
                        }
                        status.signedIn -> {
                            fetchingRepos = true
                            try {
                                git.ghRepos().fold(
                                    onSuccess = { list ->
                                        repos = list.repos
                                        RepoCache.repos = list.repos
                                        repoError = list.orgError
                                    },
                                    onFailure = { repoError = it.message ?: "Couldn't load GitHub repositories." },
                                )
                            } finally {
                                fetchingRepos = false
                            }
                        }
                        else -> {
                            repos = null
                            RepoCache.repos = null
                        }
                    }
                }
                // A few at a time: one at a time is slow with many projects, all at once floods the runtime.
                val slots = Semaphore(3)
                for (p in projects) launch {
                    slots.withPermit {
                        // One unreadable project must not stop the others (or leave them loading forever).
                        try {
                            // Counts only: new folders needn't be listed file by file.
                            statuses[p.path] = git.status(p, allUntracked = false)
                            git.originSlug(p)?.let { origins[p.path] = it } ?: origins.remove(p.path)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            statuses[p.path] = GitStatus(isRepo = false, error = GitError(e.message ?: e.toString(), toolMissing = false))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.w("ProjectsScreen", "Refresh failed", e)
            refreshError = "Couldn't refresh projects. Check the Linux runtime and pull down to retry."
            loaded = true
        } finally {
            loading = false
            refreshing = false
            fetchingRepos = false
        }
    }

    val localBySlug = origins.entries.associate { (path, slug) -> slug to File(path) }
    val q = query.trim()
    fun matches(vararg text: String?) = q.isEmpty() || text.any { it?.contains(q, ignoreCase = true) == true }
    val localShown = projects.filter { filter != Filter.Remote && matches(it.name, origins[it.path]) }
    val remoteShown = repos.orEmpty().filter { r ->
        r.slug.lowercase() !in localBySlug && filter != Filter.Local && matches(r.name, r.slug, r.description)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenHeader("Projects", onBack) {
            Box {
                IconButton(onClick = { addMenu = true }) { PhIcon(Ph.Plus, "New or clone", size = 22.dp) }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(leadingIcon = { PhIcon(Ph.FolderSimple, null, size = 18.dp) }, text = { Text("New project") }, onClick = {
                        addMenu = false
                        createError = null
                        creating = true
                    })
                    DropdownMenuItem(leadingIcon = { PhIcon(Ph.DownloadSimple, null, size = 18.dp) }, text = { Text("Clone from URL") }, onClick = {
                        addMenu = false
                        cloning = true
                    })
                    DropdownMenuItem(leadingIcon = { PhIcon(Ph.Trash, null, size = 18.dp) }, text = { Text("Free up space") }, onClick = {
                        addMenu = false
                        onFreeSpace()
                    })
                }
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { if (!loading) { refreshing = true; reload++ } },
            state = pullState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            indicator = {
                PullToRefreshDefaults.IndicatorBox(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    if (refreshing) WaveDots(Modifier.align(Alignment.Center), dotSize = 4.dp)
                    else PhIcon(Ph.ArrowClockwise, "Pull to refresh", Modifier.align(Alignment.Center), size = 18.dp)
                }
            },
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    SearchField(query, { query = it })
                }
                refreshError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        for (f in Filter.entries) Chip(f.label, selected = filter == f, onClick = { filter = f })
                    }
                }
                if (gh != null && gh?.signedIn != true) item { GitHubCard(gh, runInTerminal) }

                if (filter != Filter.Remote) {
                    item { SectionLabel("On this phone · ${localShown.size}") }
                    if (!loaded) items(3) { SkeletonCard(lines = 2) }
                    else if (localShown.isEmpty()) item {
                        EmptyNote(if (q.isNotEmpty()) "No local project matches “$q”." else "No projects yet. Create one, or clone a repository.")
                    }
                    items(localShown, key = { it.path }) { dir ->
                        LocalProjectRow(dir, statuses[dir.path], origins[dir.path], repos, Modifier.animateItem()) { onOpenProject(dir) }
                    }
                }

                if (filter != Filter.Local && gh?.signedIn == true) {
                    repoError?.let { why ->
                        item {
                            Text(why + if (repos != null) " Showing the saved list." else "",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("On GitHub · not cloned" + (repos?.let { " · ${remoteShown.size}" } ?: ""), Modifier.weight(1f))
                            if (fetchingRepos) WaveDots(dotSize = 4.dp)
                        }
                    }
                    when {
                        repos == null && fetchingRepos -> items(3) { SkeletonCard(lines = 2) }
                        remoteShown.isEmpty() && repos != null -> item {
                            EmptyNote(
                                when {
                                    q.isNotEmpty() -> "No repository matches “$q”."
                                    repos.orEmpty().isEmpty() -> "No repositories on this GitHub account yet."
                                    else -> "Every repository is already on this phone."
                                },
                            )
                        }
                    }
                    // One grouped list per owner: your account first, then each organization.
                    val groups = remoteShown.groupBy { it.owner }.entries
                        .sortedWith(compareBy({ it.value.first().isOrg }, { it.key.lowercase() }))
                    for ((owner, list) in groups) {
                        item(key = "owner:$owner") {
                            val open = owner !in collapsed
                            OwnerGroup(
                                owner = owner,
                                isOrg = list.first().isOrg,
                                repos = list,
                                open = open,
                                modifier = Modifier.animateItem(),
                                onToggle = { if (open) collapsed += owner else collapsed -= owner },
                            ) { repo ->
                                val folder = if (File(paths.projects, repo.name).exists()) repo.slug.replace('/', '-') else repo.name
                                runInTerminal(
                                    "mkdir -p ~/projects && cd ~/projects && gh repo clone ${shellQuote(repo.slug)} ${shellQuote(folder)}",
                                    "Clone ${repo.name}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (cloning) {
        CloneSheet(
            root = paths.projects,
            onDismiss = { cloning = false },
            onClone = { url, name, thenRun ->
                cloning = false
                val command = "mkdir -p ~/projects && cd ~/projects && git clone -- ${shellQuote(url)} ${shellQuote(name)}"
                if (thenRun) ProjectLaunch.cloneThenRun(context, File(paths.projects, name), command, LaunchCallbacks(onOpenProject, runInTerminal, onPreview))
                else runInTerminal(command, "Clone $name")
            },
        )
    }
    if (creating) {
        NewProjectSheet(
            root = paths.projects,
            busy = createBusy,
            error = createError,
            onEdited = { createError = null },
            onDismiss = { if (!createBusy) creating = false },
            onInstallPack = { pack -> pack.script?.let { runInTerminal(dev.yurlungr.app.runtime.setupScriptCommand(it), pack.title) } },
            onCreate = create@{ name, initGit, template ->
                if (createBusy) return@create
                createBusy = true
                createError = null
                scope.launch {
                    try {
                        val dir = File(paths.projects, name)
                        // Checked again here: the sheet's check may be stale (a pack removed meanwhile).
                        withContext(Dispatchers.IO) { Templates.missingMessage(Templates.missing(template, paths.prefix)) }?.let { error(it) }
                        val setup = ProjectLaunch.create(context, git, dir, template, initGit)
                        creating = false
                        if (template.isEmpty) onOpenProject(dir)
                        else ProjectLaunch.setupThenRun(context, dir, setup, LaunchCallbacks(onOpenProject, runInTerminal, onPreview))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        createError = e.message ?: "Couldn't create this project. Try again."
                    } finally {
                        createBusy = false
                        reload++
                    }
                }
            },
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text("Search projects and repositories") },
        leadingIcon = { PhIcon(Ph.MagnifyingGlass, null, size = 18.dp) },
        trailingIcon = if (value.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { PhIcon(Ph.X, "Clear", size = 16.dp) } }) else null,
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        keyboardOptions = Keyboards.Identifier,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}

@Composable
private fun LocalProjectRow(dir: File, status: GitStatus?, slug: String?, repos: List<GhRepo>?, modifier: Modifier, onClick: () -> Unit) {
    val repo = slug?.let { s -> repos?.firstOrNull { it.slug.lowercase() == s } }
    val tones = AppColors.status
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(Ph.FolderSimple, null, size = 22.dp, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).animateContentSize()) {
            Text(dir.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status == null) {
                SkeletonLine(Modifier.padding(top = 6.dp), width = 0.4f, height = 10.dp)
            } else {
                Text(
                    when {
                        status.error != null -> status.error.message.lineSequence().first()
                        !status.isRepo -> "Not a git repository"
                        else -> buildString {
                            append(status.branch ?: "?")
                            val n = status.changes.size
                            append(" · ").append(if (n == 0) "clean" else "$n changed")
                            if (status.ahead > 0) append(" · ↑${status.ahead}")
                            if (status.behind > 0) append(" · ↓${status.behind}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if ((status.changes.isNotEmpty())) tones.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (slug != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    PhIcon(Ph.GithubLogo, null, size = 12.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        (repo?.slug ?: slug) + (if (repo?.isOrg == true) " · organization" else "") + (if (repo?.isPrivate == true) " · private" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        StatusLabel(if (slug != null) "Cloned" else "Local", if (slug != null) tones.success else MaterialTheme.colorScheme.outline)
    }
}

/** Top of an owner's group: who owns these repositories, and whether it's an organization. */
@Composable
private fun OwnerGroup(
    owner: String,
    isOrg: Boolean,
    repos: List<GhRepo>,
    open: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onClone: (GhRepo) -> Unit,
) {
    Column(
        modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .animateContentSize(),
    ) {
        OwnerHeader(owner, isOrg, repos.size, open, onToggle)
        if (open) for (repo in repos) RemoteRepoRow(repo) { onClone(repo) }
    }
}

@Composable
private fun OwnerHeader(owner: String, isOrg: Boolean, count: Int, open: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(if (isOrg) colors.tertiaryContainer else colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            PhIcon(if (isOrg) Ph.Buildings else Ph.User, null, size = 16.dp, tint = if (isOrg) colors.onTertiaryContainer else colors.onPrimaryContainer)
        }
        Spacer(Modifier.width(10.dp))
        Text(owner, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Text(
            if (isOrg) "Organization" else "You",
            style = MaterialTheme.typography.labelSmall,
            color = if (isOrg) colors.onTertiaryContainer else colors.onPrimaryContainer,
            modifier = Modifier
                .clip(CircleShape)
                .background(if (isOrg) colors.tertiaryContainer else colors.primaryContainer)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        PhIcon(if (open) Ph.CaretDown else Ph.CaretRight, if (open) "Collapse" else "Expand", size = 14.dp, tint = colors.onSurfaceVariant)
    }
}

/** A not-cloned repository inside its owner's group. */
@Composable
private fun RemoteRepoRow(repo: GhRepo, onClone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(start = 54.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PhIcon(Ph.GithubLogo, null, size = 20.dp, tint = colors.onSurfaceVariant, modifier = Modifier.padding(start = 5.dp))
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text(repo.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                repo.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(
                        if (repo.isPrivate) "private" else "public",
                        if (repo.isFork) "fork" else null,
                        repo.updatedAt.takeIf { it > 0 }?.let {
                            "pushed " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onClone, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(34.dp)) {
                PhIcon(Ph.DownloadSimple, null, size = 15.dp)
                Spacer(Modifier.width(6.dp))
                Text("Clone")
            }
        }
    }
}

@Composable
private fun GitHubCard(gh: GhStatus?, runInTerminal: (String, String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(Ph.GithubLogo, null, size = 26.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("GitHub", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    gh == null -> ""
                    !gh.installed -> "Install the GitHub CLI to see your repositories here"
                    gh.problem == GhProblem.Broken -> "The GitHub CLI isn't working: ${gh.detail ?: "unknown error"}"
                    gh.problem == GhProblem.SignInExpired -> gh.detail ?: "Your GitHub sign-in is no longer valid."
                    gh.problem == GhProblem.Unreachable -> "GitHub can't be reached right now. Pull down to retry."
                    else -> "Sign in to see your repositories here, push, and clone private ones"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (gh?.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            gh == null -> Unit
            !gh.installed -> FilledTonalButton(onClick = { runInTerminal("pkg install -y gh", "Install gh") }) { Text("Install") }
            gh.problem == GhProblem.Broken -> FilledTonalButton(onClick = { runInTerminal("pkg reinstall -y gh", "Reinstall gh") }) { Text("Reinstall") }
            gh.problem == GhProblem.Unreachable -> Unit
            !gh.signedIn -> FilledTonalButton(onClick = {
                runInTerminal(
                    "echo 'Copy the one-time code, then press Enter: the GitHub page opens in your browser.'; " +
                        "gh auth login --hostname github.com --git-protocol https --web && gh auth setup-git",
                    "GitHub sign-in",
                )
            }) { Text("Sign in") }
        }
    }
}

private fun folderError(name: String, root: File): String? = when {
    name.isEmpty() -> null
    !PROJECT_NAME.matches(name) -> "Use up to 64 letters, digits, dots, underscores or hyphens."
    name.startsWith('.') -> "Start with a letter, digit, underscore or hyphen."
    File(root, name).exists() -> "A folder with this name already exists. Choose another name."
    else -> null
}

@Composable
private fun ProjectSheetHeader(title: String, subtitle: String, clone: Boolean, onDismiss: () -> Unit, enabled: Boolean = true) {
    val tile = if (clone) AppColors.tiles.sky else AppColors.tiles.lilac
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(tile.fill), contentAlignment = Alignment.Center) {
            PhIcon(if (clone) Ph.DownloadSimple else Ph.FolderSimple, null, size = 24.dp, tint = tile.on)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss, enabled = enabled) { PhIcon(Ph.X, "Close", size = 20.dp) }
    }
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloneSheet(root: File, onDismiss: () -> Unit, onClone: (url: String, name: String, thenRun: Boolean) -> Unit) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var thenRun by remember { mutableStateOf(ProjectLaunch.runAfterClone(context)) }
    val derived = url.trim().substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
    val finalName = name.trim().ifEmpty { derived }
    val error = folderError(finalName, root)
    val validUrl = url.isNotBlank() && !url.trim().startsWith('-') && url.trim().none { it.isWhitespace() || it.isISOControl() }
    val canClone = validUrl && finalName.isNotEmpty() && error == null
    fun submit() { if (canClone) onClone(url.trim(), finalName, thenRun) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ProjectSheetHeader("Clone from URL", "Bring a repository onto this phone and continue working here.", clone = true, onDismiss = onDismiss)
            OutlinedTextField(
                url, { url = it }, label = { Text("Repository URL") },
                placeholder = { Text("https://github.com/you/app.git") },
                supportingText = { Text("HTTPS or SSH repository URL") },
                singleLine = true, keyboardOptions = Keyboards.Url.copy(imeAction = ImeAction.Next),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                name, { name = it }, label = { Text("Folder name") },
                placeholder = { Text(derived.ifEmpty { "app" }) },
                supportingText = { Text(error ?: "~/projects/${finalName.ifEmpty { "app" }}") },
                isError = error != null, singleLine = true,
                keyboardOptions = Keyboards.Identifier.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            CheckRow("Install and run after cloning", "Open it, install what it needs, and start it if it's a web app", thenRun) {
                thenRun = it
                ProjectLaunch.setRunAfterClone(context, it)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel") }
                Button(onClick = ::submit, enabled = canClone, modifier = Modifier.weight(2f).heightIn(min = 52.dp)) {
                    PhIcon(Ph.DownloadSimple, null, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Clone repository")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewProjectSheet(
    root: File,
    busy: Boolean,
    error: String?,
    onEdited: () -> Unit,
    onDismiss: () -> Unit,
    onInstallPack: (dev.yurlungr.app.runtime.ToolSetup) -> Unit,
    onCreate: (name: String, initGit: Boolean, template: ProjectTemplate) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var initGit by rememberSaveable { mutableStateOf(true) }
    var templateId by rememberSaveable { mutableStateOf("empty") }
    val template = Templates.byId(templateId)
    val context = LocalContext.current
    val prefix = remember { RuntimePaths(context).prefix }
    // Checked off the main thread; null while checking.
    val missing by androidx.compose.runtime.produceState<List<Requirement>?>(null, template) {
        value = null
        value = withContext(Dispatchers.IO) { Templates.missing(template, prefix) }
    }
    val trimmed = name.trim()
    val nameError = folderError(trimmed, root)
    val canCreate = !busy && trimmed.isNotEmpty() && nameError == null && missing?.isEmpty() == true
    val latestBusy by rememberUpdatedState(busy)
    fun submit() { if (canCreate) onCreate(trimmed, initGit, template) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !latestBusy }),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ProjectSheetHeader("New project", "Start from a template, or with an empty folder.", clone = false, onDismiss = onDismiss, enabled = !busy)
            OutlinedTextField(
                name, { name = it; onEdited() }, label = { Text("Project name") },
                placeholder = { Text("my-project") },
                supportingText = { Text(nameError ?: "~/projects/${trimmed.ifEmpty { "my-project" }}") },
                isError = nameError != null, singleLine = true, enabled = !busy,
                keyboardOptions = Keyboards.Identifier.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = !busy) { initGit = !initGit; onEdited() }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(4.dp)) {
                    Text("Initialize Git", style = MaterialTheme.typography.titleSmall)
                    Text("Track changes in this project", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Checkbox(initGit, { initGit = it; onEdited() }, enabled = !busy)
            }
            TemplatePicker(template, missing, enabled = !busy, onSelect = { templateId = it.id; onEdited() }, onInstallPack = onInstallPack)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel") }
                Button(onClick = ::submit, enabled = canCreate, modifier = Modifier.weight(2f).heightIn(min = 52.dp)) {
                    if (busy) WaveDots(dotSize = 4.dp) else PhIcon(Ph.Plus, null, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "Creating…" else if (template.isEmpty) "Create project" else "Create and set up")
                }
            }
        }
    }
}
