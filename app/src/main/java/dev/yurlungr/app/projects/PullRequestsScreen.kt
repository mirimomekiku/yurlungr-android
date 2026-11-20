package dev.yurlungr.app.projects

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.yurlungr.app.chat.MarkdownText
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.Dot
import dev.yurlungr.app.ui.FlowBar
import dev.yurlungr.app.ui.LocalOpenChat
import dev.yurlungr.app.ui.LocalRunInTerminal
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.SectionLabel
import dev.yurlungr.app.ui.WarningBanner
import kotlinx.coroutines.launch
import java.io.File

/** Why the pull requests can't be shown yet, or that they can. */
private sealed interface PrGate {
    data object Loading : PrGate
    data object NotGitHub : PrGate
    data object NoGh : PrGate
    data class SignedOut(val detail: String?) : PrGate
    data class Ready(val slug: String) : PrGate
}

/** Diffs longer than this stay folded until asked for: rendering thousands of lines is slow on a phone. */
private const val LARGE_DIFF_LINES = 1500

/**
 * A project's open pull requests on GitHub (all, mine, or waiting for my review), and one in
 * detail: description, checks, conversation, files with their diffs, and the review, comment,
 * check out and merge actions, or handing it to an agent.
 */
@Composable
fun PullRequestsScreen(dir: File, initialNumber: Int? = null, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val git = remember { Git(paths) }
    val prs = remember { GhPullRequests(paths) }
    val runInTerminal = LocalRunInTerminal.current

    var gateReload by remember { mutableIntStateOf(0) }
    var gate by remember { mutableStateOf<PrGate>(PrGate.Loading) }
    LaunchedEffect(dir, gateReload) {
        gate = PrGate.Loading
        val slug = git.originSlug(dir)
        val gh = git.ghStatus()
        gate = when {
            slug == null -> PrGate.NotGitHub
            !gh.installed -> PrGate.NoGh
            !gh.signedIn -> PrGate.SignedOut(gh.detail)
            else -> PrGate.Ready(slug)
        }
    }

    var selected by rememberSaveable(dir.path) { mutableStateOf(initialNumber) }

    val ready = gate as? PrGate.Ready
    val number = selected
    if (ready != null && number != null) {
        PrDetailView(dir, number, git, prs, onBack = { selected = null })
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        ScreenHeader("Pull requests", onBack) {
            IconButton(onClick = { gateReload++ }) { PhIcon(Ph.ArrowClockwise, "Refresh", size = 20.dp) }
        }
        when (val g = gate) {
            PrGate.Loading -> FlowBar(Modifier.fillMaxWidth().padding(16.dp))
            PrGate.NotGitHub -> Notice("This project's origin remote isn't on GitHub, so there are no pull requests to show.")
            PrGate.NoGh -> Notice("Pull requests need the GitHub CLI (gh), part of the developer tools.") {
                Button(onClick = { runInTerminal?.invoke(dev.yurlungr.app.runtime.setupScriptCommand("dev-tools-setup.sh"), "Developer tools") }) {
                    Text("Install developer tools")
                }
                TextButton(onClick = { gateReload++ }) { Text("Retry") }
            }
            is PrGate.SignedOut -> Notice(g.detail ?: "Sign in to GitHub to see and review this project's pull requests.") {
                Button(onClick = {
                    runInTerminal?.invoke(
                        "gh auth login --hostname github.com --git-protocol https --web && gh auth setup-git",
                        "GitHub sign-in",
                    )
                }) { Text("Sign in to GitHub") }
                TextButton(onClick = { gateReload++ }) { Text("Retry") }
            }
            is PrGate.Ready -> PrList(dir, g.slug, prs, refreshKey = gateReload, onOpen = { selected = it })
        }
    }
}

@Composable
private fun Notice(text: String, actions: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
private fun PrList(dir: File, slug: String, prs: GhPullRequests, refreshKey: Int, onOpen: (Int) -> Unit) {
    var filter by rememberSaveable { mutableStateOf(PrFilter.All) }
    var result by remember { mutableStateOf<Result<List<PrSummary>>?>(null) }
    LaunchedEffect(filter, refreshKey) {
        result = null
        result = prs.list(dir, filter)
    }
    Column(Modifier.fillMaxSize()) {
        Text(slug, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in PrFilter.entries) Chip(f.label, onClick = { filter = f }, selected = f == filter)
        }
        val r = result
        when {
            r == null -> FlowBar(Modifier.fillMaxWidth().padding(16.dp))
            r.isFailure -> Notice(r.exceptionOrNull()?.message ?: "Couldn't load the pull requests.")
            else -> {
                val list = r.getOrNull().orEmpty()
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    if (list.isEmpty()) item {
                        Text(
                            when (filter) {
                                PrFilter.All -> "No open pull requests."
                                PrFilter.Mine -> "You have no open pull requests here."
                                PrFilter.ReviewRequested -> "Nobody is waiting for your review here."
                            },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(list, key = { it.number }) { pr -> PrRow(pr) { onOpen(pr.number) } }
                }
            }
        }
    }
}

@Composable
private fun checkColor(state: PrCheck.State?): Color = when (state) {
    PrCheck.State.Passed -> AppColors.status.success
    PrCheck.State.Failed -> MaterialTheme.colorScheme.error
    PrCheck.State.Pending -> AppColors.status.warning
    PrCheck.State.Skipped, null -> MaterialTheme.colorScheme.outline
}

private fun decisionLabel(decision: String?): String? = when (decision?.uppercase()) {
    "APPROVED" -> "Approved"
    "CHANGES_REQUESTED" -> "Changes requested"
    "REVIEW_REQUIRED" -> "Review required"
    else -> null
}

private fun ago(millis: Long): String {
    if (millis <= 0) return ""
    val s = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        s < 30 * 86_400 -> "${s / 86_400} d ago"
        else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrRow(pr: PrSummary, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainer).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(pr.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOf("#${pr.number}", pr.author, ago(pr.updatedAt)).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
        )
        Text("${pr.headRef} → ${pr.baseRef}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (pr.isDraft) Text("Draft", style = MaterialTheme.typography.labelMedium, color = colors.outline)
            decisionLabel(pr.reviewDecision)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
            if (pr.checks.checks.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(checkColor(pr.checks.overall), size = 7.dp)
                Spacer(Modifier.width(4.dp))
                Text(pr.checks.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Text("+${pr.additions} −${pr.deletions}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
        }
    }
}

// ---- Detail ---------------------------------------------------------------------------

private enum class PrSheet { Review, Comment, Merge, CheckoutWarning }

/** Checking out a pull request brings someone else's code onto the phone: said while it runs. */
private const val CHECKOUT_LABEL = "Checking out #%d: someone else's code, with its git hooks switched off"
private const val HOOKS_NOTE = "Its git hooks don't run on checkout: it's someone else's code. Builds, scripts and agents you start on it still run its code."

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrDetailView(dir: File, number: Int, git: Git, prs: GhPullRequests, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openChat = LocalOpenChat.current
    val colors = MaterialTheme.colorScheme

    var reload by remember { mutableIntStateOf(0) }
    var detail by remember(number) { mutableStateOf<Result<PrDetail>?>(null) }
    var diffs by remember(number) { mutableStateOf<Result<List<PrFileDiff>>?>(null) }
    var inline by remember(number) { mutableStateOf<Result<List<PrReviewComment>>?>(null) }
    LaunchedEffect(number, reload) {
        launch { detail = prs.view(dir, number) }
        launch { diffs = prs.diff(dir, number) }
        launch { inline = prs.reviewComments(dir, number) }
    }

    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    var sheet by remember { mutableStateOf<PrSheet?>(null) }
    var uncommitted by remember { mutableIntStateOf(0) }

    fun act(label: String, done: String, block: suspend () -> Result<String>) {
        if (busy != null) return
        busy = label
        message = null
        scope.launch {
            val r = try { block() } finally { busy = null }
            message = r.fold(onSuccess = { out -> (out.ifBlank { done }) to false }, onFailure = { (it.message ?: "$label failed.") to true })
            reload++
        }
    }

    fun openUrl(url: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    val pr = detail?.getOrNull()
    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding().imePadding()) {
        ScreenHeader("#$number", onBack) {
            IconButton(onClick = { reload++ }, enabled = busy == null) { PhIcon(Ph.ArrowClockwise, "Refresh", size = 20.dp) }
            pr?.url?.let { url -> IconButton(onClick = { openUrl(url) }) { PhIcon(Ph.Globe, "Open in browser", size = 20.dp) } }
        }
        if (busy != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(busy!!, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                FlowBar(Modifier.fillMaxWidth())
            }
        }
        message?.let { (text, isError) ->
            Text(
                text, style = MaterialTheme.typography.bodySmall,
                color = if (isError) colors.onErrorContainer else AppColors.status.onSuccessContainer,
                maxLines = 8, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (isError) colors.errorContainer else AppColors.status.successContainer)
                    .clickable { message = null }.padding(12.dp),
            )
        }
        val d = detail
        when {
            d == null -> FlowBar(Modifier.fillMaxWidth().padding(16.dp))
            d.isFailure -> Notice(d.exceptionOrNull()?.message ?: "Couldn't load pull request #$number.") {
                TextButton(onClick = { reload++ }) { Text("Retry") }
            }
            pr != null -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "head") { PrHeader(pr) }
                item(key = "actions") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val idle = busy == null
                        if (pr.isOpen) {
                            Button(onClick = { sheet = PrSheet.Review }, enabled = idle) { Text("Review") }
                            FilledTonalButton(onClick = { sheet = PrSheet.Comment }, enabled = idle) { Text("Comment") }
                            FilledTonalButton(onClick = {
                                scope.launch {
                                    // gh carries uncommitted changes over to the PR branch, or refuses: say so first.
                                    val s = git.status(dir)
                                    uncommitted = s.changes.size
                                    if (uncommitted > 0) sheet = PrSheet.CheckoutWarning
                                    else act(CHECKOUT_LABEL.format(number), "Checked out #$number (${pr.headRef}). $HOOKS_NOTE") { prs.checkout(dir, number) }
                                }
                            }, enabled = idle) { Text("Check out") }
                            FilledTonalButton(onClick = { sheet = PrSheet.Merge }, enabled = idle) { Text("Merge…") }
                        }
                        pr.url?.let { url -> OutlinedButton(onClick = { openUrl(url) }) { Text("Open in browser") } }
                    }
                }
                if (openChat != null) item(key = "ai") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiRow(Ph.Eye, "Ask an agent to review", "It reads the diff and reports findings here, without changing or posting anything") {
                            openChat(dir, PrPrompts.review(pr))
                        }
                        if (pr.isOpen && pr.checks.failed.isNotEmpty()) {
                            AiRow(Ph.MagicWand, "Ask an agent to fix the failing checks", "On the PR branch; it commits the fix but doesn't push") {
                                scope.launch { openChat(dir, PrPrompts.fixChecks(pr, git.status(dir).branch)) }
                            }
                        }
                    }
                }
                item(key = "checks") { ChecksCard(pr.checks, onOpen = ::openUrl) }
                item(key = "body-label") { SectionLabel("Description") }
                item(key = "body") {
                    if (pr.body.isBlank()) Text("No description.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    else Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainer).padding(14.dp)) {
                        MarkdownText(pr.body, allowLocalFiles = false)
                    }
                }
                conversation(pr.conversation)
                reviewComments(inline)
                files(pr, diffs)
            }
        }
    }

    val current = pr
    when (sheet) {
        PrSheet.Review -> if (current != null) ReviewSheet(current, onDismiss = { sheet = null }) { kind, body ->
            sheet = null
            act("Sending your review", "${kind.label}: sent.") { prs.review(dir, number, kind, body) }
        }
        PrSheet.Comment -> CommentSheet(number, onDismiss = { sheet = null }) { body ->
            sheet = null
            act("Adding your comment", "Comment added.") { prs.comment(dir, number, body) }
        }
        PrSheet.Merge -> if (current != null) MergeSheet(current, onDismiss = { sheet = null }) { method ->
            sheet = null
            act("Merging #$number", "Merged #$number.") { prs.merge(dir, number, method, current.headRefOid) }
        }
        PrSheet.CheckoutWarning -> AppSheet(
            "Uncommitted changes", onDismiss = { sheet = null }, icon = Ph.Warning, tile = AppColors.tiles.peach,
            subtitle = "This project has $uncommitted uncommitted change${if (uncommitted == 1) "" else "s"}.",
            actions = {
                OutlinedButton(onClick = { sheet = null }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = {
                    sheet = null
                    act(CHECKOUT_LABEL.format(number), "Checked out #$number. $HOOKS_NOTE") { prs.checkout(dir, number) }
                }, modifier = Modifier.weight(1f)) { Text("Check out anyway") }
            },
        ) {
            Text(
                "Switching to the pull request's branch takes them along, or fails if they clash with it. Commit or stash them first (Git actions → Branches & stashes) to keep things apart.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
            Text(HOOKS_NOTE, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        null -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrHeader(pr: PrDetail) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(pr.title, style = MaterialTheme.typography.titleLarge)
        Text(
            listOfNotNull(
                when {
                    pr.state.equals("MERGED", true) -> "Merged"
                    pr.state.equals("CLOSED", true) -> "Closed"
                    pr.isDraft -> "Draft"
                    else -> "Open"
                },
                pr.author,
                decisionLabel(pr.reviewDecision),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
        )
        Text("${pr.headRef} → ${pr.baseRef}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
        Text(
            "+${pr.additions} −${pr.deletions} in ${pr.files.size} file${if (pr.files.size == 1) "" else "s"}" +
                if (pr.mergeable.equals("CONFLICTING", true)) " · has conflicts with ${pr.baseRef}" else "",
            style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun AiRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tile = AppColors.tiles.lilac
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(tile.fill), contentAlignment = Alignment.Center) {
            PhIcon(icon, null, size = 18.dp, tint = tile.on)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(6.dp))
                PhIcon(Ph.Sparkle, "AI", size = 13.dp, tint = colors.primary)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChecksCard(checks: CheckSummary, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(checkColor(checks.overall))
            Spacer(Modifier.width(8.dp))
            Text("Checks: ${checks.label}", style = MaterialTheme.typography.titleSmall)
        }
        for (c in checks.failed.take(15)) {
            Row(
                Modifier.fillMaxWidth().then(if (c.url != null) Modifier.clickable { onOpen(c.url) } else Modifier).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhIcon(Ph.XCircle, null, size = 16.dp, tint = colors.error)
                Spacer(Modifier.width(8.dp))
                Text(listOfNotNull(c.workflow, c.name).distinct().joinToString(" / "), style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (checks.failed.size > 15) Text("… and ${checks.failed.size - 15} more failing", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

private fun reviewVerb(state: String?): String = when (state) {
    "APPROVED" -> "approved"
    "CHANGES_REQUESTED" -> "requested changes"
    "DISMISSED" -> "reviewed (dismissed)"
    null -> "commented"
    else -> "reviewed"
}

private fun LazyListScope.conversation(entries: List<PrComment>) {
    item(key = "conv-label") { SectionLabel("Conversation (${entries.size})") }
    if (entries.isEmpty()) item(key = "conv-empty") {
        Text("No comments yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    itemsIndexed(entries, key = { i, _ -> "conv-$i" }) { _, c ->
        CommentCard("${c.author} ${reviewVerb(c.reviewState)} · ${ago(c.createdAt)}", c.body)
    }
}

private fun LazyListScope.reviewComments(result: Result<List<PrReviewComment>>?) {
    val list = result?.getOrNull()
    if (result?.isFailure == true) item(key = "inline-error") {
        Text("Inline review comments couldn't be loaded: ${result.exceptionOrNull()?.message}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (list.isNullOrEmpty()) return
    item(key = "inline-label") { SectionLabel("Review comments (${list.size})") }
    for ((path, group) in list.groupBy { it.path }) {
        item(key = "inline-path-$path") {
            Text(path, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        }
        itemsIndexed(group, key = { i, _ -> "inline-$path-$i" }) { _, c ->
            CommentCard(listOfNotNull(c.line?.let { "Line $it" }, c.author, ago(c.createdAt).ifEmpty { null }).joinToString(" · "), c.body,
                indent = c.inReplyTo != null)
        }
    }
}

@Composable
private fun CommentCard(header: String, body: String, indent: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(start = if (indent) 16.dp else 0.dp).clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(header, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        if (body.isNotBlank()) MarkdownText(body, allowLocalFiles = false)
    }
}

private fun LazyListScope.files(pr: PrDetail, diffs: Result<List<PrFileDiff>>?) {
    val list = diffs?.getOrNull()
    item(key = "files-label") { SectionLabel("Files changed (${list?.size ?: pr.files.size})") }
    when {
        diffs == null -> item(key = "files-loading") { FlowBar(Modifier.fillMaxWidth()) }
        diffs.isFailure -> {
            item(key = "files-error") {
                Text("The diff couldn't be loaded: ${diffs.exceptionOrNull()?.message}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(pr.files, key = { "file-${it.path}" }) { f -> FileHeader(f.path, null, f.additions, f.deletions, expanded = null) {} }
        }
        else -> items(list.orEmpty(), key = { "diff-${it.path}" }) { f -> FileDiffItem(f) }
    }
}

@Composable
private fun FileHeader(path: String, oldPath: String?, added: Int, removed: Int, expanded: Boolean?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainer)
            .then(if (expanded != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded != null) {
            PhIcon(if (expanded) Ph.CaretDown else Ph.CaretRight, if (expanded) "Fold" else "Show diff", size = 14.dp, tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            oldPath?.let { Text("from $it", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(8.dp))
        Text("+$added", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = AppColors.status.success, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text("−$removed", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = colors.error, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FileDiffItem(f: PrFileDiff) {
    // Folded by default: a PR can touch hundreds of files.
    val state = remember(f.path) { mutableStateMapOf<String, Boolean>() }
    val expanded = state["open"] == true
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FileHeader(f.path, f.oldPath, f.added, f.removed, expanded) { state["open"] = !expanded }
        if (expanded) when {
            f.binary -> Text("Binary file (no text diff)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            f.lineCount > LARGE_DIFF_LINES && state["large"] != true -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Large diff: ${f.lineCount} lines.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { state["large"] = true }) { Text("Show it") }
            }
            else -> UnifiedDiff(f.text)
        }
    }
}

// ---- Sheets ---------------------------------------------------------------------------

@Composable
private fun ReviewSheet(pr: PrDetail, onDismiss: () -> Unit, onSubmit: (PrReviewKind, String) -> Unit) {
    var kind by remember { mutableStateOf(PrReviewKind.Approve) }
    var body by rememberSaveable { mutableStateOf("") }
    // GitHub rejects "request changes" and "comment" reviews without text.
    val canSend = kind == PrReviewKind.Approve || body.isNotBlank()
    AppSheet(
        "Review #${pr.number}", onDismiss = onDismiss, subtitle = pr.title, icon = Ph.Eye, tile = AppColors.tiles.lilac,
        actions = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = { onSubmit(kind, body.trim()) }, enabled = canSend, modifier = Modifier.weight(1f)) { Text(kind.label) }
        },
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in PrReviewKind.entries) Chip(k.label, onClick = { kind = k }, selected = k == kind)
        }
        OutlinedTextField(
            body, { body = it },
            label = { Text(if (kind == PrReviewKind.Approve) "Comment (optional)" else "What should change?") },
            minLines = 4, modifier = Modifier.fillMaxWidth(),
        )
        Text("Markdown works. The review is posted to GitHub as you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CommentSheet(number: Int, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var body by rememberSaveable { mutableStateOf("") }
    AppSheet(
        "Comment on #$number", onDismiss = onDismiss, icon = Ph.ChatCircle, tile = AppColors.tiles.sky,
        actions = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = { onSubmit(body.trim()) }, enabled = body.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Comment") }
        },
    ) {
        OutlinedTextField(body, { body = it }, label = { Text("Comment") }, minLines = 4, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun MergeSheet(pr: PrDetail, onDismiss: () -> Unit, onMerge: (PrMergeMethod) -> Unit) {
    var method by remember { mutableStateOf(PrMergeMethod.Squash) }
    AppSheet(
        "Merge #${pr.number}?", onDismiss = onDismiss, subtitle = "${pr.headRef} into ${pr.baseRef}", icon = Ph.GitMerge, tile = AppColors.tiles.lime,
        actions = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = { onMerge(method) }, enabled = !pr.isDraft && pr.headRefOid != null, modifier = Modifier.weight(1f)) { Text("Merge") }
        },
    ) {
        if (pr.isDraft) WarningBanner("Drafts can't be merged: mark it ready for review on GitHub first.")
        if (pr.headRefOid == null) WarningBanner("Which commit to merge isn't known: refresh the pull request first.")
        if (pr.mergeable.equals("CONFLICTING", true)) WarningBanner("It has conflicts with ${pr.baseRef}: GitHub will refuse the merge.")
        if (pr.checks.failed.isNotEmpty()) WarningBanner("${pr.checks.failed.size} check${if (pr.checks.failed.size == 1) " is" else "s are"} failing.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in PrMergeMethod.entries) Chip(m.label, onClick = { method = m }, selected = m == method)
        }
        Text(method.description + ". This changes ${pr.baseRef} on GitHub and can't be undone from here.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        pr.headRefOid?.let { oid ->
            Text("Merges exactly what you're looking at (commit ${oid.take(7)}). If anything was pushed since, GitHub refuses and nothing is merged.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
