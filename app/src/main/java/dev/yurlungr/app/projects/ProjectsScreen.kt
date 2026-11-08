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
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.launch
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
@Composable
fun ProjectsScreen(
    refreshTick: Int,
    onBack: () -> Unit,
    onOpenProject: (File) -> Unit,
    runInTerminal: (command: String, name: String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val git = remember { Git(paths) }
    val scope = rememberCoroutineScope()

    var projects by remember { mutableStateOf<List<File>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val statuses = remember { mutableStateMapOf<String, GitStatus>() }
    val origins = remember { mutableStateMapOf<String, String>() } // dir path -> github slug
    var gh by remember { mutableStateOf<GhStatus?>(null) }
    var repos by remember { mutableStateOf(RepoCache.repos) }
    var fetchingRepos by remember { mutableStateOf(false) }
    var repoError by remember { mutableStateOf(false) }
    var cloning by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var query by rememberSaveable { mutableStateOf("") }
    val collapsed = remember { mutableStateListOf<String>() } // owners whose repo group is folded

    LaunchedEffect(refreshTick, reload) {
        projects = withContext(Dispatchers.IO) {
            paths.projects.mkdirs()
            paths.projects.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
                .sortedByDescending { it.lastModified() }
        }
        loaded = true
        val status = git.ghStatus()
        gh = status
        if (status.signedIn) {
            fetchingRepos = true
            launch {
                val list = git.ghRepos()
                repoError = list == null
                if (list != null) {
                    repos = list
                    RepoCache.repos = list
                }
                fetchingRepos = false
            }
        }
        for (p in projects) {
            statuses[p.path] = git.status(p)
            git.originSlug(p)?.let { origins[p.path] = it } ?: origins.remove(p.path)
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
                        creating = true
                    })
                    DropdownMenuItem(leadingIcon = { PhIcon(Ph.DownloadSimple, null, size = 18.dp) }, text = { Text("Clone from URL") }, onClick = {
                        addMenu = false
                        cloning = true
                    })
                }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                SearchField(query, { query = it })
            }
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
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("On GitHub · not cloned" + (repos?.let { " · ${remoteShown.size}" } ?: ""), Modifier.weight(1f))
                        if (fetchingRepos) WaveDots(dotSize = 4.dp)
                    }
                }
                when {
                    repos == null && fetchingRepos -> items(3) { SkeletonCard(lines = 2) }
                    repos == null && repoError -> item {
                        EmptyNote("Couldn't load your GitHub repositories. Check the connection and that gh is signed in.")
                    }
                    remoteShown.isEmpty() -> item {
                        EmptyNote(if (q.isNotEmpty()) "No repository matches “$q”." else "Every repository is already on this phone.")
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

    if (cloning) {
        CloneDialog(
            onDismiss = { cloning = false },
            onClone = { url, name ->
                cloning = false
                runInTerminal(
                    "mkdir -p ~/projects && cd ~/projects && git clone ${shellQuote(url)} ${shellQuote(name)}",
                    "Clone $name",
                )
            },
        )
    }
    if (creating) {
        NewProjectDialog(
            onDismiss = { creating = false },
            onCreate = { name, initGit ->
                creating = false
                scope.launch {
                    val dir = File(paths.projects, name)
                    withContext(Dispatchers.IO) { dir.mkdirs() }
                    if (initGit) git.init(dir)
                    reload++
                    onOpenProject(dir)
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
                    else -> "Sign in to see your repositories here, push, and clone private ones"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            gh == null -> Unit
            !gh.installed -> FilledTonalButton(onClick = { runInTerminal("pkg install -y gh", "Install gh") }) { Text("Install") }
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

@Composable
private fun CloneDialog(onDismiss: () -> Unit, onClone: (url: String, name: String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val derived = url.trim().trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
    val finalName = name.trim().ifEmpty { derived }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone a repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("URL") }, placeholder = { Text("https://github.com/you/app") }, singleLine = true, keyboardOptions = Keyboards.Url)
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Folder") },
                    placeholder = { Text(derived.ifEmpty { "app" }) },
                    supportingText = { Text("~/projects/$finalName") },
                    singleLine = true,
                    keyboardOptions = Keyboards.Identifier,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onClone(url.trim(), finalName) }, enabled = url.isNotBlank() && PROJECT_NAME.matches(finalName)) { Text("Clone") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (name: String, initGit: Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var initGit by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Name") },
                    supportingText = { Text("Letters, digits, . _ -") },
                    singleLine = true,
                    keyboardOptions = Keyboards.Identifier,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(initGit, { initGit = it })
                    Text("Initialize a git repository")
                }
            }
        },
        confirmButton = { Button(onClick = { onCreate(name.trim(), initGit) }, enabled = PROJECT_NAME.matches(name.trim())) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
