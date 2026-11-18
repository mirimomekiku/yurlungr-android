package dev.yurlungr.app.projects

/**
 * A known way something breaks on Android/Termux, and what to do about it. [command] runs in the
 * project folder (in a terminal, only when the user taps it); without one the fix is advice, best
 * done by the agent. [env] are variables that make the failing tool behave here.
 */
data class KnownFix(
    val id: String,
    val title: String,
    val explanation: String,
    val command: String? = null,
    val env: Map<String, String> = emptyMap(),
    /** What to tell an agent asked to fix it: the change it should make. */
    val agentHint: String = explanation,
)

/**
 * Recognizes failures that are specific to the phone (prebuilt binaries for desktop platforms,
 * missing compilers, watcher limits…) in install, run, build and check output. Only commands
 * known to be right are offered; anything less certain is advice for the agent.
 */
object KnownFixes {
    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]|\u001B[()][A-Za-z0-9]")

    /** Only the end of a huge log is searched: that's where the failure is. */
    private const val MAX_CHARS = 300_000

    /** The package manager of a project's Node stack ("pnpm" from "Node.js (pnpm)"), npm otherwise. */
    fun packageManager(stacks: List<DetectedStack>): String =
        stacks.firstOrNull { it.id == "node" }?.name?.let { Regex("\\((\\w+)\\)").find(it)?.groupValues?.get(1) } ?: "npm"

    private fun install(pm: String) = when (pm) {
        "pnpm" -> "pnpm install"
        "yarn" -> "yarn install"
        "bun" -> "bun install"
        else -> "npm install"
    }

    private fun addDev(pm: String, spec: String) = when (pm) {
        "pnpm" -> "pnpm add -D $spec"
        "yarn" -> "yarn add -D $spec"
        "bun" -> "bun add -d $spec"
        else -> "npm install --save-dev $spec"
    }

    /** A clean reinstall: node_modules from another machine, or one npm half-filled, is the usual cause. */
    private fun reinstall(pm: String) = "rm -rf node_modules && ${install(pm)}"

    /** Termux packages with prebuilt Python modules (the Python data & science pack). */
    internal val PYTHON_PACKAGES = mapOf(
        "numpy" to "python-numpy", "pandas" to "python-pandas", "scipy" to "python-scipy", "pillow" to "python-pillow",
        "cryptography" to "python-cryptography", "lxml" to "python-lxml", "matplotlib" to "matplotlib",
    )

    /** Lets the project's venv see Termux's packages, if it was made without that. */
    internal const val VENV_SYSTEM_SITE =
        "if [ -f .venv/pyvenv.cfg ]; then sed -i 's/^include-system-site-packages = false\$/include-system-site-packages = true/' .venv/pyvenv.cfg; fi"

    private class Rule(val patterns: List<Regex>, val fix: (MatchResult, String) -> KnownFix?)

    private fun rx(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE))

    private val RULES: List<Rule> = listOf(
        Rule(listOf(
            rx("You installed esbuild for another platform"),
            rx("The package \"@esbuild/android-arm64\" could not be found"),
            rx("Cannot find module '@esbuild/android-arm64'"),
            rx("Host version \"[\\d.]+\" does not match binary version \"[\\d.]+\""),
        )) { _, pm ->
            KnownFix(
                "esbuild", "esbuild's binary doesn't match this phone",
                "node_modules was installed for another platform, or the install skipped esbuild's Android binary " +
                    "(@esbuild/android-arm64). Reinstalling the dependencies on the phone fetches the right one.",
                command = reinstall(pm),
            )
        },
        Rule(listOf(
            rx("Failed to load SWC binary for android/arm64"),
            rx("Attempted to load @next/swc-android-arm64"),
            rx("@next/swc-android-arm64[^\\n]*not (?:been )?installed"),
        )) { _, pm ->
            KnownFix(
                "next-swc", "Next.js has no native compiler for Android",
                "Next.js uses its WebAssembly compiler instead when @next/swc-wasm-nodejs is installed at the same version as next. " +
                    "It's slower, but it works here.",
                command = addDev(pm, "@next/swc-wasm-nodejs@\"\$(node -p \"require('next/package.json').version\")\""),
                agentHint = "Install @next/swc-wasm-nodejs at the exact version of next (Next.js then falls back to its WASM compiler), " +
                    "and avoid options that need the native SWC binary (e.g. some experimental SWC plugins).",
            )
        },
        Rule(listOf(
            rx("Your current platform \"android\" and architecture \"arm64\" combination is not yet supported by the native Rollup build"),
        )) { _, _ ->
            KnownFix(
                "rollup-old", "This Rollup version has no Android build",
                "Rollup 4 publishes @rollup/rollup-android-arm64; older versions don't. Updating Rollup (or Vite), or using " +
                    "@rollup/wasm-node through package overrides, fixes it.",
                agentHint = "Rollup's native build doesn't support android/arm64 in this version. Update rollup/vite to a version that " +
                    "ships @rollup/rollup-android-arm64, or alias rollup to @rollup/wasm-node with overrides/resolutions.",
            )
        },
        Rule(listOf(
            rx("Cannot find module '@rollup/rollup-android-arm64'"),
        )) { _, pm ->
            KnownFix(
                "rollup", "Rollup's Android binary is missing",
                "npm sometimes skips optional platform packages when the lockfile was made on another computer. A clean reinstall " +
                    "usually brings @rollup/rollup-android-arm64. If it doesn't, the lockfile needs regenerating (the agent can do that).",
                command = reinstall(pm),
            )
        },
        Rule(listOf(
            rx("Could not load the \"sharp\" module using the android-arm64 runtime"),
            rx("Something went wrong installing the \"sharp\" module"),
            rx("sharp: Prebuilt libvips [^\\n]*android"),
        )) { _, pm ->
            KnownFix(
                "sharp", "sharp has no Android build",
                "sharp can run on its WebAssembly build instead" + (if (pm == "npm") ": this installs it." else " (@img/sharp-wasm32, installed with npm's --cpu=wasm32)."),
                command = if (pm == "npm") "npm install --cpu=wasm32 sharp" else null,
                agentHint = "sharp has no prebuilt binary for android-arm64. Install its WebAssembly build " +
                    "(`npm install --cpu=wasm32 sharp`, i.e. @img/sharp-wasm32 at the same version as sharp), or make image processing optional.",
            )
        },
        Rule(listOf(
            rx("Cannot find module '[^'\\n]*lightningcss[^'\\n]*\\.node'"),
            rx("lightningcss[^\\n]*android-arm64"),
        )) { _, _ ->
            KnownFix(
                "lightningcss", "lightningcss has no binary for this phone",
                "Tailwind CSS 4, Next.js and some bundlers load lightningcss's native binary, which this version doesn't ship for Android. " +
                    "A different version, or a setup without it, is needed.",
                agentHint = "lightningcss can't load its native binary on android-arm64. Find a version that ships lightningcss-android-arm64, " +
                    "or configure the build to not need lightningcss (e.g. PostCSS instead).",
            )
        },
        Rule(listOf(
            rx("dlopen failed: library \"(?:libc\\.so\\.6|libstdc\\+\\+\\.so\\.6|ld-linux-aarch64\\.so\\.1|libgcc_s\\.so\\.1)\" not found"),
            rx("Cannot find module '[^'\\n]*(?:linux|darwin|win32)[^'\\n]*\\.node'"),
            rx("No prebuilt binaries found \\(target=[^\\n]*platform=android"),
        )) { _, _ ->
            KnownFix(
                "prebuilt", "A prebuilt binary isn't made for Android",
                "A package downloaded a native binary built for desktop Linux (or none for Android). It needs building on the phone, " +
                    "a WebAssembly or pure-JS alternative, or a version that supports android-arm64.",
                agentHint = "A native module ships no android-arm64 binary (or a glibc one that can't load on Android/Bionic). " +
                    "Swap it for a pure-JS/WASM alternative, or make it build from source here.",
            )
        },
        Rule(listOf(rx("Undefined variable android_ndk_path"))) { _, pm ->
            KnownFix(
                "gyp-ndk", "node-gyp looks for the Android NDK",
                "Building native add-ons on Termux needs node-gyp told there's no NDK. This reinstalls with GYP_DEFINES set.",
                command = "GYP_DEFINES=\"android_ndk_path=''\" ${install(pm)}",
                env = mapOf("GYP_DEFINES" to "android_ndk_path=''"),
            )
        },
        Rule(listOf(
            rx("bcrypt_lib\\.node"),
            rx("bcrypt@[\\d.]+ install[^\\n]*node-pre-gyp"),
            rx("node-pre-gyp[^\\n]*\\bbcrypt\\b"),
        )) { _, _ ->
            KnownFix(
                "bcrypt", "bcrypt needs compiling on the phone",
                "bcryptjs is a pure-JavaScript drop-in with the same API: slower, but nothing to compile.",
                agentHint = "Replace the native bcrypt package with bcryptjs (same API): update package.json and the imports.",
            )
        },
        Rule(listOf(
            rx("Failed building wheel for ([\\w.\\-]+)"),
            rx("Building wheel for ([\\w.\\-]+) \\([^)]*\\) did not run successfully"),
            rx("Could not build wheels for ([\\w.\\-]+(?:, [\\w.\\-]+)*)"),
            rx("Failed to build installable wheels for some pyproject\\.toml based projects \\(([^)\\n]+)\\)"),
            // uv: "Failed to build `numpy==1.26.4`"
            rx("Failed to build `([\\w.\\-]+)"),
        )) { m, _ ->
            val names = m.groupValues[1].split(',').map { it.trim().substringBefore("==").lowercase() }
                .filter { it.isNotEmpty() && it != "which" }.distinct()
            val termux = names.mapNotNull { PYTHON_PACKAGES[it] }.distinct()
            if (names.isEmpty()) null else if (termux.isNotEmpty()) KnownFix(
                "python-wheel", "${names.joinToString()} won't compile here; use Termux's build",
                "Termux has ${termux.joinToString()} prebuilt. This installs it and lets the project's .venv see it " +
                    "(system site-packages), so pip uses it instead of compiling. Run the setup again afterwards. " +
                    "If the project pins a different version, pip may still try to build it: relax the pin.",
                command = "pkg install -y ${termux.joinToString(" ")} && $VENV_SYSTEM_SITE",
                agentHint = "Building ${names.joinToString()} from source fails on the phone. Termux packages ${termux.joinToString()} provide it prebuilt; " +
                    "use a venv created with --system-site-packages and relax version pins so pip accepts the installed one.",
            ) else KnownFix(
                "python-build", "A Python package needs compiling",
                "${names.joinToString()} has no wheel for Android, so pip builds it from source, which needs a compiler and its libraries " +
                    "(pkg install clang make pkg-config, plus rust for Rust-based packages), or a version or alternative that doesn't.",
                agentHint = "pip couldn't build ${names.joinToString()} on Android (no prebuilt wheel). Install what the build needs with pkg, " +
                    "pick a version with a pure-Python fallback, or replace the dependency.",
            )
        },
        Rule(listOf(
            rx("Cargo, the Rust package manager, is not installed"),
            rx("(?:^|\\s)cargo: (?:command )?not found"),
        )) { _, _ ->
            KnownFix("rust", "Rust isn't installed", "This needs Rust's compiler and Cargo.", command = "pkg install -y rust")
        },
        Rule(listOf(
            rx("linker [`'\"]cc[`'\"] not found"),
            rx("C compiler \"(\\w+)\" not found"),
            rx("exec: \"(gcc|cc|clang|g\\+\\+)\": executable file not found"),
            rx("(?:^|[\\s:])(clang|gcc|cc|c\\+\\+|g\\+\\+|make): (?:command )?not found"),
            rx("gyp ERR! stack Error: not found: (make)"),
            rx("unable to execute '(gcc|cc|clang)': No such file"),
            rx("error: command '(gcc|cc|clang)' failed: No such file"),
        )) { m, _ ->
            val gcc = m.groupValues.getOrNull(1).orEmpty().startsWith("g")
            KnownFix(
                "compiler", "No C compiler installed",
                "Native code (Rust crates, cgo, node-gyp, Python extensions) needs a C compiler. Termux's is clang." +
                    (if (gcc) " Tools that ask for gcc work with CC=clang." else ""),
                command = "pkg install -y clang make",
                env = if (gcc) mapOf("CC" to "clang", "CXX" to "clang++") else emptyMap(),
            )
        },
        Rule(listOf(
            rx("ENOSPC: System limit for number of file watchers reached"),
            rx("EMFILE: too many open files, watch"),
            rx("inotify watch limit reached"),
            rx("Error: EMFILE: too many open files"),
        )) { _, _ ->
            KnownFix(
                "watchers", "Android limits file watchers",
                "The dev server watches more files than Android allows. Make it poll instead.",
                env = mapOf("CHOKIDAR_USEPOLLING" to "1", "WATCHPACK_POLLING" to "true"),
                agentHint = "File watching hits Android's limit (ENOSPC/EMFILE). Switch the dev server to polling " +
                    "(CHOKIDAR_USEPOLLING=1, WATCHPACK_POLLING=true, or Vite's server.watch.usePolling) and ignore node_modules and build folders.",
            )
        },
        Rule(listOf(
            rx("EADDRINUSE[^\\n]*?:(\\d{2,5})\\b"),
            rx("listen tcp [^\\n]*?:(\\d{2,5}): bind: address already in use"),
            // uvicorn
            rx("bind on address \\('[^'\\n]*', (\\d{2,5})\\)[^\\n]*address already in use"),
            rx("Port (\\d{2,5}) is (?:already )?in use(?!, trying)"),
            rx("address already in use(?:[^\\n]*?:(\\d{2,5})\\b)?"),
            rx("\\[Errno 98\\] Address already in use"),
        )) { m, _ ->
            val port = m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
            KnownFix(
                "port", if (port != null) "Port $port is already in use" else "The port is already in use",
                "Another server (an earlier run, or one in a terminal tab) is listening on ${port?.let { "port $it" } ?: "that port"}. " +
                    "Stop it in Servers or its terminal, or run this on another port.",
                agentHint = "The server can't listen: ${port?.let { "port $it" } ?: "its port"} is taken. Make the port configurable (PORT env var) " +
                    "rather than killing other processes.",
            )
        },
    )

    /** The fixes [log] calls for, most specific first, each once. [pm]: the project's Node package manager. */
    fun match(log: String, pm: String = "npm"): List<KnownFix> {
        if (log.isBlank()) return emptyList()
        val text = ANSI.replace(log.takeLast(MAX_CHARS), "").replace("\r", "")
        val out = LinkedHashMap<String, KnownFix>()
        for (rule in RULES) {
            val m = rule.patterns.firstNotNullOfOrNull { it.find(text) } ?: continue
            val fix = rule.fix(m, pm) ?: continue
            out.putIfAbsent(fix.id, fix)
        }
        return out.values.toList()
    }
}
