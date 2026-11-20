package dev.yurlungr.app.web

import dev.yurlungr.app.agents.AgentExtensions
import dev.yurlungr.app.projects.Git
import dev.yurlungr.app.projects.SpaceCleaner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A secret or setting a provider's CLI reads from the environment. */
internal data class DeployKey(val env: String, val label: String, val secret: Boolean)

/** Where a built site can go, and how its CLI is told (flags checked against each CLI's docs). */
internal enum class DeployProvider(val label: String, val keys: List<DeployKey>, val siteLabel: String, val siteRequired: Boolean, val tokenHelp: String) {
    Cloudflare(
        "Cloudflare Pages",
        listOf(DeployKey("CLOUDFLARE_API_TOKEN", "API token", true), DeployKey("CLOUDFLARE_ACCOUNT_ID", "Account ID", false)),
        "Pages project name", siteRequired = true,
        "Create a token with the Cloudflare Pages: Edit permission. The account ID is on your Cloudflare dashboard.",
    ),
    Netlify(
        "Netlify",
        listOf(DeployKey("NETLIFY_AUTH_TOKEN", "Personal access token", true)),
        "Site name or ID (optional)", siteRequired = false,
        "Create a personal access token in Netlify under User settings → Applications.",
    ),
    Vercel(
        "Vercel",
        listOf(DeployKey("VERCEL_TOKEN", "Token", true)),
        "Project name (optional)", siteRequired = false,
        "Create a token in Vercel under Account settings → Tokens.",
    );

    val envNames get() = keys.map { it.env }
}

/** What zipping a folder did. */
internal data class ZipSummary(val files: Int, val bytes: Long, val skippedLinks: Int)

/**
 * Build output and deploys for web projects: which folder a build writes, the commands that build,
 * preview and publish it, and zipping it. Pure where it can be; nothing here runs a process.
 */
internal object WebDeploy {
    /** Folders a build of [kind] usually writes, most likely first. */
    fun outputCandidates(kind: WebKind?, framework: String): List<String> = when {
        framework == "SvelteKit" -> listOf("build", "dist")
        kind == WebKind.Next -> listOf("out")
        kind == WebKind.Cra -> listOf("build")
        kind == WebKind.Nuxt -> listOf(".output/public", "dist")
        kind == WebKind.Astro || kind == WebKind.Vite || kind == WebKind.Parcel || kind == WebKind.Webpack || kind == WebKind.Expo -> listOf("dist")
        kind == WebKind.Angular -> listOf("dist")
        kind == WebKind.Static -> listOf("public", ".")
        else -> listOf("dist", "build", "out", "public")
    }

    /**
     * The output folder under [dir], relative ("dist", "dist/app/browser", "."): the first candidate that
     * holds an index.html (looking two levels into it for Angular-style `dist/<app>/browser`), else the first
     * one that exists, else the first candidate (not built yet).
     */
    fun detectOutput(dir: File, candidates: List<String>): String {
        fun hasIndex(f: File) = File(f, "index.html").isFile
        for (c in candidates) {
            val f = if (c == ".") dir else File(dir, c)
            if (!f.isDirectory) continue
            if (hasIndex(f)) return c
            // dist/<app>/browser (Angular 17+) or dist/<app>.
            val inner = f.listFiles()?.filter { it.isDirectory && !AgentExtensions.isLink(it) }?.sortedBy { it.name }.orEmpty().take(16)
            for (sub in inner) {
                if (hasIndex(sub)) return "$c/${sub.name}".removePrefix("./")
                File(sub, "browser").takeIf { hasIndex(it) }?.let { return "$c/${sub.name}/browser".removePrefix("./") }
            }
        }
        return candidates.firstOrNull { c -> c != "." && File(dir, c).isDirectory } ?: candidates.firstOrNull() ?: "dist"
    }

    /**
     * A user-typed output folder as a folder inside [dir] (or [dir] itself for "." or blank), or null when it
     * leaves the project: absolute paths, "..", or a link that points elsewhere.
     */
    fun resolveOutput(dir: File, relative: String): File? {
        val typed = relative.trim()
        // Judged before any slash is trimmed: "/tmp/x" must not turn into the project's own "tmp/x".
        if (typed.startsWith("/") || File(typed).isAbsolute) return null
        val rel = typed.trimEnd('/').ifEmpty { "." }
        if (rel.startsWith("~") || File(rel).isAbsolute || rel.contains('\\') || rel.contains('\u0000')) return null
        if (rel.split('/').any { it == ".." }) return null
        val root = runCatching { SpaceCleaner.realFile(dir) }.getOrNull() ?: return null
        if (rel == ".") return root
        val target = File(root, rel)
        // Judged by where it really is, so `dist -> ~` is refused rather than zipped or deployed.
        val real = runCatching { SpaceCleaner.realFile(target) }.getOrNull() ?: return null
        return if (real.path.startsWith(root.path + File.separator)) target else null
    }

    private val PLAIN_SCRIPT = Regex("^[A-Za-z0-9_.:-]+$")

    /** The script that builds: `build`, else nothing (we don't guess at others). */
    fun buildScript(project: WebProject): String? = "build".takeIf { it in project.scriptCommands }

    /**
     * The shell command that builds [project] with its package manager: dependencies first when they're
     * missing, then `<pm> run build`. Null when there's no build script.
     */
    fun buildCommand(project: WebProject): String? {
        val script = buildScript(project)?.takeIf { PLAIN_SCRIPT.matches(it) } ?: return null
        val pm = (project.manager ?: WebPackageManager.Npm).executable
        val q = Git::shellQuote
        val install = if (project.dependenciesPresent) "" else "cd ${q(project.installDirectory.invariantSeparatorsPath)} && $pm install && "
        return "${install}cd ${q(project.directory.invariantSeparatorsPath)} && $pm run $script"
    }

    // Cloudflare project names: lowercase letters, digits, dashes. Netlify/Vercel names and IDs: also dots and underscores.
    private val SITE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")

    fun isValidSite(name: String) = SITE.matches(name)

    /**
     * The deploy CLIs, pinned to exact releases (each a few weeks old when picked, not "latest"), so a
     * hijacked new release can't reach the deploy token the day it's published. Bump them together.
     */
    val DEPLOY_CLIS: Map<DeployProvider, String> = mapOf(
        DeployProvider.Cloudflare to "wrangler@4.156.0",
        DeployProvider.Netlify to "netlify-cli@27.16.0",
        DeployProvider.Vercel to "vercel@65.8.0",
    )

    /** Where the deploy CLIs come from, whatever a project's or the user's .npmrc says. */
    const val NPM_REGISTRY = "https://registry.npmjs.org/"

    /** The app-owned folder a deploy of [projectDir] runs in (CLIs keep site links there), under [cliDir]. */
    fun deployWorkDir(cliDir: File, projectDir: File): File {
        val path = runCatching { projectDir.canonicalPath }.getOrDefault(projectDir.absolutePath)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        return File(cliDir, "work/$hash")
    }

    /**
     * The deploy command for [provider]. Tokens never appear in it: the CLI reads them from the environment,
     * which the command clears before the terminal's shell takes over. Null when a needed site name is
     * missing or isn't a plain name.
     *
     * The project's own code must not get the token. `npx` run inside the project prefers the project's
     * `node_modules/.bin/wrangler` (whoever wrote the repository controls it), and npm reads the project's
     * `.npmrc` (a `registry=` line can serve a look-alike CLI). So the pinned CLI runs from [cliDir], an
     * app-owned npm prefix, with an empty app-owned user config and the public registry named explicitly,
     * in a per-project folder under it ([deployWorkDir]); the project is only the folder that's uploaded.
     */
    fun deployCommand(provider: DeployProvider, projectDir: File, cliDir: File, outDir: File, site: String?, npm: String = "npm"): String? {
        val q = Git::shellQuote
        val name = site?.trim().orEmpty()
        if (name.isNotEmpty() && !isValidSite(name)) return null
        if (provider.siteRequired && name.isEmpty()) return null
        val out = q(outDir.invariantSeparatorsPath)
        val prefix = q(cliDir.invariantSeparatorsPath)
        val npmrc = q(File(cliDir, "npmrc").invariantSeparatorsPath)
        val work = q(deployWorkDir(cliDir, projectDir).invariantSeparatorsPath)
        // --yes: install the pinned CLI without asking.
        val exec = "${q(npm)} exec --yes --prefix $prefix --userconfig $npmrc --registry=$NPM_REGISTRY --package=${DEPLOY_CLIS.getValue(provider)} --"
        val cli = when (provider) {
            DeployProvider.Cloudflare -> "$exec wrangler pages deploy $out --project-name ${q(name)}"
            // --no-build: the site is already built (deploy otherwise runs the build again).
            DeployProvider.Netlify -> "$exec netlify deploy --dir $out --prod --no-build" + if (name.isEmpty()) "" else " --site ${q(name)}"
            DeployProvider.Vercel -> "$exec vercel deploy $out --prod --yes" + if (name.isEmpty()) "" else " --project ${q(name)}"
        }
        // The user config is emptied every time, so nothing left in it can point npm elsewhere.
        val setup = "mkdir -p $work && : > $npmrc && cd $work"
        // The tab stays open as a shell afterwards: it must not keep the tokens.
        return "$setup && $cli; _deploy_exit=\$?; unset ${provider.envNames.joinToString(" ")}; (exit \$_deploy_exit)"
    }

    /** The environment for a deploy, or the label of the first value still missing. */
    fun deployEnv(provider: DeployProvider, values: Map<String, String>): Result<Map<String, String>> {
        val env = LinkedHashMap<String, String>()
        for (k in provider.keys) {
            val v = values[k.env]?.trim().orEmpty()
            if (v.isEmpty() || v.any { it == '\n' || it == '\r' || it == '\u0000' }) return Result.failure(IllegalStateException("Add your ${provider.label} ${k.label.lowercase()} first."))
            env[k.env] = v
        }
        return Result.success(env)
    }

    /** "••••abcd": enough to tell tokens apart, never the token. */
    fun mask(secret: String): String = if (secret.length <= 8) "••••" else "••••" + secret.takeLast(4)

    const val MAX_ZIP_BYTES = 512L * 1024 * 1024
    const val MAX_ZIP_FILES = 50_000

    /**
     * Zips [dir]'s contents into [dest]. Links are skipped (never followed, so nothing outside [dir] goes in),
     * as are hidden files and node_modules. Fails, leaving no zip, past [maxBytes] or [maxFiles].
     */
    fun zipFolder(dir: File, dest: File, maxBytes: Long = MAX_ZIP_BYTES, maxFiles: Int = MAX_ZIP_FILES): ZipSummary {
        require(dir.isDirectory && !AgentExtensions.isLink(dir)) { "${dir.name} isn't a folder." }
        var files = 0
        var bytes = 0L
        var links = 0
        dest.parentFile?.mkdirs()
        try {
            ZipOutputStream(dest.outputStream().buffered()).use { zip ->
                val stack = ArrayDeque<Pair<File, String>>()
                stack.addLast(dir to "")
                while (stack.isNotEmpty()) {
                    val (folder, prefix) = stack.removeLast()
                    for (f in folder.listFiles().orEmpty().sortedBy { it.name }) {
                        if (AgentExtensions.isLink(f)) { links++; continue }
                        val name = prefix + f.name
                        // Hidden files (.env, .git…) and dependencies are never part of a site; .well-known is.
                        if (f.name.startsWith(".") && f.name != ".well-known" || f.name == "node_modules") continue
                        if (f.isDirectory) {
                            stack.addLast(f to "$name/")
                        } else if (f.isFile) {
                            files++
                            bytes += f.length()
                            check(files <= maxFiles) { "More than $maxFiles files: too many to zip here." }
                            check(bytes <= maxBytes) { "Bigger than ${maxBytes / (1024 * 1024)} MB: too big to zip here." }
                            zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                            f.inputStream().use { it.copyTo(zip, 64 * 1024) }
                            zip.closeEntry()
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
        return ZipSummary(files, bytes, links)
    }
}
