package dev.yurlungr.app.runtime

/** Which setup provides a tool, i.e. what to run when it's missing or broken. */
enum class ToolSetup(
    val script: String?,
    val title: String,
    /** Optional packs: always listed in System checks with their own Install button; missing is just info. */
    val optional: Boolean = false,
    /** Termux packages the pack installs (exported to the script as `YURLUNGR_PACKAGES_<NAME>`). */
    val packages: List<String> = emptyList(),
    /** Disk space the script requires, in MB. */
    val spaceMb: Int = 0,
    /** File under the prefix that exists once installed; used for the agent notes. */
    val marker: String? = null,
    /** One line for the agent notes. */
    val blurb: String = "",
) {
    DevTools("dev-tools-setup.sh", "Developer tools"),
    Agents("agents-setup.sh", "Agents"),
    AndroidSdk("android-sdk-setup.sh", "Android SDK"),
    Python("python-setup.sh", "Python", true, listOf("python", "python-pip", "uv"), 450, "bin/python", "Python 3 with pip and uv"),
    BuildTools("build-tools-setup.sh", "Build tools", true, listOf("clang", "make", "cmake", "pkg-config", "binutils", "protobuf", "ninja", "autoconf", "automake", "libtool", "bison", "flex", "gettext", "ccache"), 1100, "bin/clang", "clang, make, cmake, ninja, autotools, bison, flex, ccache, pkg-config, protoc"),
    Utils("utils-setup.sh", "Utilities", true, listOf("jq", "openssh", "zip", "unzip", "wget", "tar", "rsync", "which", "file", "tree"), 160, "bin/jq", "jq, ssh, zip, wget, rsync, which, file, tree"),
    Editors("editors-setup.sh", "Editors", true, listOf("nano", "vim", "neovim", "helix", "micro", "emacs"), 450, "bin/vim", "nano, vim, Neovim, Helix, micro and Emacs"),
    Go("go-setup.sh", "Go", true, listOf("golang"), 700, "bin/go", "Go"),
    Rust("rust-setup.sh", "Rust", true, listOf("rust"), 1500, "bin/cargo", "Rust (rustc, cargo)"),
    Ruby("ruby-setup.sh", "Ruby", true, listOf("ruby"), 400, "bin/ruby", "Ruby with gem"),
    JsRuntimes("js-runtimes-setup.sh", "JavaScript runtimes", true, listOf("pnpm", "yarn", "bun", "deno"), 500, "bin/pnpm", "pnpm, Yarn, Bun and Deno (Node.js is in the developer tools)"),
    Jvm("jvm-setup.sh", "Java & Kotlin", true, listOf("openjdk-21", "kotlin", "maven", "gradle"), 700, "bin/kotlinc", "JDK 21, Kotlin, Maven and Gradle (other JDKs: Settings → Development environment)"),
    Php("php-setup.sh", "PHP", true, listOf("php", "composer"), 250, "bin/composer", "PHP with Composer"),
    Dart("dart-setup.sh", "Dart", true, listOf("dart"), 700, "bin/dart", "Dart SDK (Flutter itself isn't available on Android)"),
    Dotnet("dotnet-setup.sh", ".NET", true, listOf("dotnet8.0"), 1200, "bin/dotnet", ".NET 8 SDK"),
    Databases("databases-setup.sh", "Databases", true, listOf("sqlite", "postgresql", "mariadb", "redis"), 700, "bin/psql", "SQLite, PostgreSQL, MariaDB and Redis (servers start on demand)"),
    Swift("swift-setup.sh", "Swift", true, listOf("swift"), 1500, "bin/swift", "Swift toolchain"),
    Zig("zig-setup.sh", "Zig", true, listOf("zig"), 500, "bin/zig", "Zig"),
    Elixir("elixir-setup.sh", "Elixir", true, listOf("erlang", "elixir"), 700, "bin/elixir", "Erlang/OTP and Elixir with mix"),
    Lua("lua-setup.sh", "Lua", true, listOf("lua54", "luarocks"), 80, "bin/luarocks", "Lua 5.4 with LuaRocks"),
    Crystal("crystal-setup.sh", "Crystal", true, listOf("crystal"), 400, "bin/crystal", "Crystal (with shards)"),
    Nim("nim-setup.sh", "Nim", true, listOf("nim"), 400, "bin/nim", "Nim with nimble"),
    Haskell("haskell-setup.sh", "Haskell", true, listOf("ghc", "cabal-install"), 3500, "bin/ghc", "GHC and cabal (about 3 GB)"),
    Scala("scala-setup.sh", "Scala", true, listOf("scala"), 400, "bin/scala", "Scala 3 (runs on the JDK)"),
    Dlang("dlang-setup.sh", "D", true, listOf("ldc"), 300, "bin/ldc2", "D with the LDC compiler"),
    Perl("perl-setup.sh", "Perl", true, listOf("perl"), 150, "bin/perl", "Perl 5 with cpan"),
    Shell("shell-setup.sh", "Shell & terminal", true, listOf("tmux", "htop", "zsh", "fish", "starship"), 150, "bin/tmux", "tmux, htop, zsh, fish and starship"),
    CliTools("cli-tools-setup.sh", "CLI tools", true, listOf("fd", "bat", "eza", "hyperfine", "lazygit", "gitui", "git-lfs", "shellcheck", "shfmt", "tree-sitter", "fzf", "zoxide", "ncdu", "git-delta"), 300, "bin/lazygit", "fd, bat, eza, hyperfine, lazygit, gitui, git-lfs, shellcheck, shfmt, tree-sitter, fzf, zoxide, ncdu, delta"),
    Servers("servers-setup.sh", "Servers & tunnels", true, listOf("nginx", "caddy", "cloudflared", "mosquitto"), 200, "bin/caddy", "nginx, Caddy, cloudflared (share a dev server through a public link) and Mosquitto (MQTT)"),
    DevOps("devops-setup.sh", "DevOps", true, listOf("kubectl", "helm", "android-tools"), 250, "bin/kubectl", "kubectl, Helm, and adb/fastboot (for example adb to this phone over wireless debugging)"),
    Media("media-setup.sh", "Media & docs", true, listOf("ffmpeg", "imagemagick", "graphviz", "texlive-installer"), 900, "bin/ffmpeg", "ffmpeg, ImageMagick, Graphviz, and the TeX Live installer (run texlive-installer for LaTeX)"),
    LocalAi("local-ai-setup.sh", "Local AI", true, listOf("ollama", "llama-cpp"), 300, "bin/ollama", "Ollama and llama.cpp (models download separately)"),
    Distros("distros-setup.sh", "Linux distros", true, listOf("proot-distro"), 100, "bin/proot-distro", "proot-distro: Ubuntu, Debian or Arch in proot, for glibc-only tools (slower; distros are large)"),
    NativeLibs("native-libs-setup.sh", "Native libraries", true, listOf("libvips", "libcairo", "pango", "libjpeg-turbo", "libpng", "libxml2", "libxslt", "libffi", "libsqlite"), 250, "lib/libvips.so", "What native npm and pip packages link against: libvips (sharp), cairo and pango (canvas), image, XML and SQLite libraries"),
    PyScience("python-science-setup.sh", "Python data & science", true, listOf("python", "python-numpy", "python-pandas", "python-scipy", "python-pillow", "python-cryptography", "python-lxml", "matplotlib"), 600, "bin/f2py", "Prebuilt numpy, pandas, scipy, Pillow, cryptography, lxml and matplotlib (no compiling; project venvs see them)"),
    Debugging("debugging-setup.sh", "Debugging", true, listOf("gdb", "lldb", "strace", "ltrace", "valgrind"), 500, "bin/gdb", "gdb, lldb, strace, ltrace and valgrind"),
    LanguageServers("language-servers-setup.sh", "Language servers", true, listOf("rust-analyzer", "gopls", "lua-language-server", "ruff"), 400, "bin/rust-analyzer", "rust-analyzer, gopls, lua-language-server and ruff (clangd comes with Build tools)"),
    Networking("networking-setup.sh", "Networking", true, listOf("dnsutils", "iproute2", "netcat-openbsd", "socat", "nmap", "xh", "httpie", "openssl-tool"), 150, "bin/dig", "dig, ip, nc, socat, nmap, xh, HTTPie and openssl"),
    Security("security-setup.sh", "Security & signing", true, listOf("gnupg", "age", "git-crypt"), 60, "bin/gpg", "GnuPG (signed commits), age and git-crypt"),
    DataTools("data-tools-setup.sh", "Data tools", true, listOf("yq", "xmlstarlet", "miller"), 60, "bin/yq", "yq (YAML), xmlstarlet (XML) and Miller (CSV)"),
    TaskRunners("task-runners-setup.sh", "Task runners & watchers", true, listOf("just", "direnv", "watchexec", "entr", "inotify-tools"), 60, "bin/just", "just, direnv, watchexec, entr and inotify-tools"),
    Docs("docs-setup.sh", "Docs & static sites", true, listOf("pandoc", "hugo", "zola", "mdbook", "mandoc"), 400, "bin/pandoc", "pandoc, Hugo, Zola, mdBook and man pages (mandoc)"),
    Archives("archives-setup.sh", "Archives", true, listOf("7zip", "xz-utils", "zstd", "brotli"), 60, "bin/zstd", "7-Zip, xz, zstd and brotli"),
    Ocr("ocr-pdf-setup.sh", "OCR & PDF", true, listOf("tesseract", "poppler", "qpdf"), 250, "bin/tesseract", "Tesseract OCR, poppler (pdftotext…) and qpdf"),
    Browser("browser-setup.sh", "Headless browser", true, listOf("chromium"), 900, "bin/chromium-browser", "Chromium (from Termux's x11 repository) for Puppeteer and Selenium tests; Playwright doesn't support Android"),
}

/**
 * A command-line tool the app or its users rely on. [probe] is a shell command that prints a
 * version line and exits 0 when the tool works; exit 127 means it isn't installed.
 */
data class Tool(
    val id: String,
    val name: String,
    val probe: String,
    val setup: ToolSetup,
    /** Shown as a failure (not just a warning) when broken, because core features need it. */
    val essential: Boolean = false,
    /** Only checked when this file under the prefix exists (e.g. an agent that's been installed). */
    val onlyIf: String? = null,
    /** Seconds the probe may take; JVM tools need longer than the default. */
    val timeoutS: Int = Toolchain.PROBE_TIMEOUT_S,
)

/** Result of probing one [Tool]. */
data class ToolState(val tool: Tool, val exitCode: Int, val firstLine: String) {
    val ok: Boolean get() = exitCode == 0 && firstLine.isNotBlank()
    val missing: Boolean get() = exitCode == 127
    val timedOut: Boolean get() = exitCode == 124
}

/**
 * The single list of packages and tools: setup scripts read it (via the generated
 * `toolchain.sh`, see [shellManifest]) and System checks probe it, so they can't drift apart.
 */
object Toolchain {
    /** Packages for git/GitHub/Node work and for running npm-based CLIs (termux-exec fixes their shebangs). */
    val DEV_PACKAGES = listOf("termux-exec", "git", "gh", "nodejs-lts", "ripgrep", "curl")

    /** Extra packages the agents need, before and after enabling the glibc repository. */
    val AGENT_PACKAGES = listOf("patchelf", "glibc-repo")
    val AGENT_GLIBC_PACKAGES = listOf("glibc-runner", "codex")

    /** Termux's `gradle` only bootstraps the 8.x wrapper; projects build with ./gradlew. */
    val SDK_PACKAGES = listOf("openjdk-21", "gradle", "aapt", "aapt2", "aidl", "apksigner", "d8", "unzip", "curl")
    const val SDK_PLATFORM = "android-36"
    const val SDK_BUILD_TOOLS = "36.0.0"

    val tools = listOf(
        Tool("git", "Git", "git --version", ToolSetup.DevTools, essential = true),
        Tool("gh", "GitHub CLI", "gh --version | sed -n 1p", ToolSetup.DevTools),
        Tool("node", "Node.js", "node --version", ToolSetup.DevTools),
        Tool("npm", "npm", "npm --version", ToolSetup.DevTools),
        Tool("rg", "ripgrep", "rg --version | sed -n 1p", ToolSetup.DevTools),
        Tool("gemini", "Gemini CLI", "gemini --version", ToolSetup.Agents, onlyIf = "bin/gemini"),
        Tool("codex", "Codex", "codex --version", ToolSetup.Agents, onlyIf = "bin/codex"),
        Tool("codex-acp", "Codex ACP adapter", "test -x \"\$(command -v codex-acp)\" && echo \"\$(command -v codex-acp)\"", ToolSetup.Agents, onlyIf = "bin/codex-acp"),
        Tool("claude", "Claude Code", "claude --version", ToolSetup.Agents, onlyIf = "libexec/yurlungr/claude-glibc"),
        Tool("claude-agent-acp", "Claude ACP adapter", "test -x \"\$(command -v claude-agent-acp)\" && echo \"\$(command -v claude-agent-acp)\"", ToolSetup.Agents, onlyIf = "bin/claude-agent-acp"),
        // Its Go runtime logs a startup line to stderr ("logging before google.Init") on every run.
        Tool("agy", "Antigravity", "agy --version 2>/dev/null", ToolSetup.Agents, onlyIf = "bin/agy"),
        Tool("qwen", "Qwen Code", "qwen --version", ToolSetup.Agents, onlyIf = "bin/qwen"),
        Tool("kimi", "Kimi Code", "kimi --version", ToolSetup.Agents, onlyIf = "bin/kimi"),
        Tool("auggie", "Auggie", "auggie --version", ToolSetup.Agents, onlyIf = "bin/auggie"),
        Tool("commandcode", "Command Code", "commandcode --version", ToolSetup.Agents, onlyIf = "bin/commandcode"),
        Tool("copilot", "Copilot", "copilot --version", ToolSetup.Agents, onlyIf = "bin/copilot"),
        Tool("goose", "Goose", "goose --version", ToolSetup.Agents, onlyIf = "libexec/yurlungr/bin/goose"),
        Tool("opencode", "OpenCode", "opencode --version", ToolSetup.Agents, onlyIf = "bin/opencode"),
        Tool("mcode", "MiniMax Code", "mcode --version", ToolSetup.Agents, onlyIf = "bin/mcode"),
        Tool("pi", "Pi", "pi --version", ToolSetup.Agents, onlyIf = "bin/pi"),
        Tool("pi-acp", "Pi ACP adapter", "test -x \"\$(command -v pi-acp)\" && echo \"\$(command -v pi-acp)\"", ToolSetup.Agents, onlyIf = "bin/pi-acp"),
        Tool("qodercli", "Qoder CLI", "qodercli --version", ToolSetup.Agents, onlyIf = "bin/qodercli"),
        Tool("cline", "Cline", "cline --version", ToolSetup.Agents, onlyIf = "bin/cline"),
        Tool("kilo", "Kilo Code", "kilo --version", ToolSetup.Agents, onlyIf = "bin/kilo"),
        Tool("cursor-agent", "Cursor", "cursor-agent --version", ToolSetup.Agents, onlyIf = "bin/cursor-agent"),
        Tool("amp", "Amp", "amp --version", ToolSetup.Agents, onlyIf = "bin/amp"),
        Tool("amp-acp", "Amp ACP adapter", "test -x \"\$(command -v amp-acp)\" && echo \"\$(command -v amp-acp)\"", ToolSetup.Agents, onlyIf = "bin/amp-acp"),
        Tool("vibe-acp", "Mistral Vibe", "test -x \"\$(command -v vibe-acp)\" && echo \"\$(command -v vibe-acp)\"", ToolSetup.Agents, onlyIf = "bin/vibe-acp"),
        Tool("kiro-cli", "Kiro", "kiro-cli --version", ToolSetup.Agents, onlyIf = "bin/kiro-cli"),
        Tool("fast-agent-acp", "fast-agent", "test -x \"\$(command -v fast-agent-acp)\" && echo \"\$(command -v fast-agent-acp)\"", ToolSetup.Agents, onlyIf = "bin/fast-agent-acp"),
        Tool("minion-code", "Minion Code", "test -x \"\$(command -v minion-code)\" && echo \"\$(command -v minion-code)\"", ToolSetup.Agents, onlyIf = "bin/minion-code"),
        Tool("python", "Python", "python --version", ToolSetup.Python),
        Tool("pip", "pip", "pip --version", ToolSetup.Python),
        Tool("clang", "Clang", "clang --version", ToolSetup.BuildTools),
        Tool("make", "make", "make --version", ToolSetup.BuildTools),
        Tool("protoc", "protoc", "protoc --version", ToolSetup.BuildTools),
        Tool("cmake", "CMake", "cmake --version", ToolSetup.BuildTools),
        Tool("pkg-config", "pkg-config", "pkg-config --version", ToolSetup.BuildTools),
        Tool("jq", "jq", "jq --version", ToolSetup.Utils),
        Tool("ssh", "OpenSSH", "ssh -V", ToolSetup.Utils),
        Tool("zip", "zip", "zip --version | sed -n 2p", ToolSetup.Utils),
        Tool("wget", "wget", "wget --version | sed -n 1p", ToolSetup.Utils),
        Tool("rsync", "rsync", "rsync --version | sed -n 1p", ToolSetup.Utils),
        Tool("nano", "nano", "nano --version | sed -n 1p", ToolSetup.Editors),
        Tool("vim", "Vim", "vim --version | sed -n 1p", ToolSetup.Editors),
        Tool("nvim", "Neovim", "nvim --version | sed -n 1p", ToolSetup.Editors),
        Tool("hx", "Helix", "hx --version", ToolSetup.Editors),
        Tool("micro", "micro", "micro -version | sed -n 1p", ToolSetup.Editors),
        Tool("emacs", "Emacs", "emacs --version | sed -n 1p", ToolSetup.Editors),
        Tool("go", "Go", "go version", ToolSetup.Go),
        Tool("rustc", "Rust", "rustc --version", ToolSetup.Rust),
        Tool("cargo", "Cargo", "cargo --version", ToolSetup.Rust),
        Tool("ruby", "Ruby", "ruby --version", ToolSetup.Ruby),
        Tool("gem", "RubyGems", "gem --version", ToolSetup.Ruby),
        Tool("uv", "uv", "uv --version", ToolSetup.Python),
        Tool("pnpm", "pnpm", "pnpm --version", ToolSetup.JsRuntimes),
        Tool("yarn", "Yarn", "yarn --version", ToolSetup.JsRuntimes),
        Tool("bun", "Bun", "bun --version", ToolSetup.JsRuntimes),
        Tool("deno", "Deno", "deno --version | sed -n 1p", ToolSetup.JsRuntimes),
        Tool("kotlinc", "Kotlin", "kotlinc -version 2>&1 | sed -n 1p", ToolSetup.Jvm, timeoutS = SLOW_PROBE_S),
        Tool("javac", "javac", "javac -version 2>&1 | sed -n 1p", ToolSetup.Jvm),
        Tool("mvn", "Maven", "mvn --version | sed -n 1p", ToolSetup.Jvm, timeoutS = SLOW_PROBE_S),
        Tool("php", "PHP", "php --version | sed -n 1p", ToolSetup.Php),
        Tool("composer", "Composer", "composer --version 2>/dev/null | sed -n 1p", ToolSetup.Php),
        Tool("dart", "Dart", "dart --version 2>&1 | sed -n 1p", ToolSetup.Dart),
        Tool("dotnet", ".NET", "dotnet --version", ToolSetup.Dotnet),
        Tool("sqlite3", "SQLite", "sqlite3 --version", ToolSetup.Databases),
        Tool("psql", "PostgreSQL", "psql --version", ToolSetup.Databases),
        Tool("mariadb", "MariaDB", "mariadb --version", ToolSetup.Databases),
        Tool("redis-server", "Redis", "redis-server --version", ToolSetup.Databases),
        Tool("swift", "Swift", "swift --version 2>&1 | sed -n 1p", ToolSetup.Swift),
        Tool("zig", "Zig", "zig version", ToolSetup.Zig),
        Tool("elixir", "Elixir", "elixir --version 2>&1 | tail -n 1", ToolSetup.Elixir),
        Tool("lua", "Lua", "lua5.4 -v", ToolSetup.Lua),
        Tool("luarocks", "LuaRocks", "luarocks --version | sed -n 1p", ToolSetup.Lua),
        Tool("crystal", "Crystal", "crystal --version | sed -n 1p", ToolSetup.Crystal),
        Tool("nim", "Nim", "nim --version | sed -n 1p", ToolSetup.Nim),
        Tool("ghc", "GHC", "ghc --version", ToolSetup.Haskell),
        Tool("cabal", "cabal", "cabal --version | sed -n 1p", ToolSetup.Haskell),
        Tool("scala", "Scala", "scala --version 2>&1 | sed -n 1p", ToolSetup.Scala, timeoutS = SLOW_PROBE_S),
        Tool("ldc2", "LDC", "ldc2 --version | sed -n 1p", ToolSetup.Dlang),
        Tool("perl", "Perl", "perl -e 'print \"perl \$^V\\n\"'", ToolSetup.Perl),
        Tool("tmux", "tmux", "tmux -V", ToolSetup.Shell),
        Tool("htop", "htop", "htop --version | sed -n 1p", ToolSetup.Shell),
        Tool("zsh", "zsh", "zsh --version", ToolSetup.Shell),
        Tool("fish", "fish", "fish --version", ToolSetup.Shell),
        Tool("starship", "starship", "starship --version | sed -n 1p", ToolSetup.Shell),
        Tool("fd", "fd", "fd --version", ToolSetup.CliTools),
        Tool("bat", "bat", "bat --version", ToolSetup.CliTools),
        Tool("eza", "eza", "eza --version | grep -m1 -o 'v[0-9][0-9.]*'", ToolSetup.CliTools),
        Tool("hyperfine", "hyperfine", "hyperfine --version", ToolSetup.CliTools),
        Tool("lazygit", "lazygit", "lazygit --version | grep -o 'version=[^,]*' | sed -n 1p", ToolSetup.CliTools),
        Tool("gitui", "gitui", "gitui --version", ToolSetup.CliTools),
        Tool("git-lfs", "git-lfs", "git-lfs version", ToolSetup.CliTools),
        Tool("shellcheck", "ShellCheck", "shellcheck --version | sed -n 2p", ToolSetup.CliTools),
        Tool("shfmt", "shfmt", "shfmt --version", ToolSetup.CliTools),
        Tool("tree-sitter", "tree-sitter", "tree-sitter --version", ToolSetup.CliTools),
        Tool("nginx", "nginx", "nginx -v 2>&1", ToolSetup.Servers),
        Tool("caddy", "Caddy", "caddy version", ToolSetup.Servers),
        Tool("cloudflared", "cloudflared", "cloudflared --version", ToolSetup.Servers),
        Tool("mosquitto", "Mosquitto", "command -v mosquitto", ToolSetup.Servers),
        Tool("kubectl", "kubectl", "kubectl version --client 2>/dev/null | sed -n 1p", ToolSetup.DevOps),
        Tool("helm", "Helm", "helm version --short", ToolSetup.DevOps),
        Tool("adb", "adb", "adb version | sed -n 1p", ToolSetup.DevOps),
        Tool("ffmpeg", "ffmpeg", "ffmpeg -version | sed -n 1p", ToolSetup.Media),
        Tool("magick", "ImageMagick", "magick --version | sed -n 1p", ToolSetup.Media),
        Tool("dot", "Graphviz", "dot -V 2>&1", ToolSetup.Media),
        Tool("ollama", "Ollama", "ollama -v 2>&1 | tail -n 1", ToolSetup.LocalAi),
        Tool("llama", "llama.cpp", "command -v llama-cli || command -v llama-server", ToolSetup.LocalAi),
        Tool("proot-distro", "proot-distro", "command -v proot-distro", ToolSetup.Distros),
        Tool("libvips", "libvips", "ls \"\$PREFIX\"/lib/libvips.so* 2>/dev/null | sed -n 1p", ToolSetup.NativeLibs),
        Tool("libcairo", "cairo", "ls \"\$PREFIX\"/lib/libcairo.so* 2>/dev/null | sed -n 1p", ToolSetup.NativeLibs),
        Tool("libxml2", "libxml2", "ls \"\$PREFIX\"/lib/libxml2.so* 2>/dev/null | sed -n 1p", ToolSetup.NativeLibs),
        Tool("numpy", "numpy", "python -c 'import numpy; print(\"numpy\", numpy.__version__)'", ToolSetup.PyScience, timeoutS = SLOW_PROBE_S),
        Tool("pandas", "pandas", "python -c 'import pandas; print(\"pandas\", pandas.__version__)'", ToolSetup.PyScience, timeoutS = SLOW_PROBE_S),
        Tool("PIL", "Pillow", "python -c 'import PIL; print(\"PIL\", PIL.__version__)'", ToolSetup.PyScience, timeoutS = SLOW_PROBE_S),
        Tool("gdb", "gdb", "gdb --version | sed -n 1p", ToolSetup.Debugging),
        Tool("lldb", "lldb", "lldb --version | sed -n 1p", ToolSetup.Debugging),
        Tool("strace", "strace", "strace -V | sed -n 1p", ToolSetup.Debugging),
        Tool("valgrind", "valgrind", "valgrind --version", ToolSetup.Debugging),
        Tool("rust-analyzer", "rust-analyzer", "rust-analyzer --version", ToolSetup.LanguageServers),
        Tool("gopls", "gopls", "gopls version | sed -n 1p", ToolSetup.LanguageServers),
        Tool("lua-language-server", "lua-language-server", "lua-language-server --version", ToolSetup.LanguageServers),
        Tool("ruff", "ruff", "ruff --version", ToolSetup.LanguageServers),
        Tool("dig", "dig", "dig -v 2>&1", ToolSetup.Networking),
        Tool("nc", "netcat", "command -v nc", ToolSetup.Networking),
        Tool("socat", "socat", "socat -V | grep -m1 version", ToolSetup.Networking),
        Tool("nmap", "nmap", "nmap --version | sed -n 1p", ToolSetup.Networking),
        Tool("xh", "xh", "xh --version | sed -n 1p", ToolSetup.Networking),
        Tool("http", "HTTPie", "http --version", ToolSetup.Networking, timeoutS = SLOW_PROBE_S),
        Tool("openssl", "openssl", "openssl version", ToolSetup.Networking),
        Tool("gpg", "GnuPG", "gpg --version | sed -n 1p", ToolSetup.Security),
        Tool("age", "age", "age --version", ToolSetup.Security),
        Tool("git-crypt", "git-crypt", "git-crypt --version", ToolSetup.Security),
        Tool("yq", "yq", "yq --version", ToolSetup.DataTools),
        Tool("xmlstarlet", "xmlstarlet", "xmlstarlet --version | sed -n 1p", ToolSetup.DataTools),
        Tool("mlr", "Miller", "mlr --version", ToolSetup.DataTools),
        Tool("just", "just", "just --version", ToolSetup.TaskRunners),
        Tool("direnv", "direnv", "direnv version", ToolSetup.TaskRunners),
        Tool("watchexec", "watchexec", "watchexec --version | sed -n 1p", ToolSetup.TaskRunners),
        Tool("entr", "entr", "command -v entr", ToolSetup.TaskRunners),
        Tool("inotifywait", "inotify-tools", "command -v inotifywait", ToolSetup.TaskRunners),
        Tool("pandoc", "pandoc", "pandoc --version | sed -n 1p", ToolSetup.Docs),
        Tool("hugo", "Hugo", "hugo version", ToolSetup.Docs),
        Tool("zola", "Zola", "zola --version", ToolSetup.Docs),
        Tool("mdbook", "mdBook", "mdbook --version", ToolSetup.Docs),
        Tool("man", "man", "command -v man", ToolSetup.Docs),
        Tool("7z", "7-Zip", "command -v 7zz || command -v 7z", ToolSetup.Archives),
        Tool("xz", "xz", "xz --version | sed -n 1p", ToolSetup.Archives),
        Tool("zstd", "zstd", "zstd --version", ToolSetup.Archives),
        Tool("brotli", "brotli", "brotli --version", ToolSetup.Archives),
        Tool("tesseract", "Tesseract", "tesseract --version 2>&1 | sed -n 1p", ToolSetup.Ocr),
        Tool("pdftotext", "poppler", "pdftotext -v 2>&1 | sed -n 1p", ToolSetup.Ocr),
        Tool("qpdf", "qpdf", "qpdf --version | sed -n 1p", ToolSetup.Ocr),
        Tool("chromium", "Chromium", "command -v chromium-browser || command -v chromium", ToolSetup.Browser),
        Tool("which", "which", "which --version | sed -n 1p", ToolSetup.Utils),
        Tool("file", "file", "file --version | sed -n 1p", ToolSetup.Utils),
        Tool("tree", "tree", "tree --version", ToolSetup.Utils),
        Tool("ninja", "ninja", "ninja --version", ToolSetup.BuildTools),
        Tool("ccache", "ccache", "ccache --version | sed -n 1p", ToolSetup.BuildTools),
        Tool("fzf", "fzf", "fzf --version", ToolSetup.CliTools),
        Tool("zoxide", "zoxide", "zoxide --version", ToolSetup.CliTools),
        Tool("ncdu", "ncdu", "ncdu --version", ToolSetup.CliTools),
        Tool("delta", "delta", "delta --version", ToolSetup.CliTools),
        Tool("java", "Java (JDK)", "java -version 2>&1 | sed -n 1p", ToolSetup.AndroidSdk, onlyIf = "bin/java"),
        Tool("aapt2", "aapt2", "aapt2 version", ToolSetup.AndroidSdk, onlyIf = "bin/aapt2"),
    )

    /** Seconds each probe may take; agent CLIs start Node, which is slow on phones. */
    const val PROBE_TIMEOUT_S = 20

    /** For tools that start a JVM (Kotlin, Scala, Maven): several seconds each on a phone. */
    const val SLOW_PROBE_S = 60

    /**
     * How many probes run at once. Each is ~5 processes (subshells, timeout, bash, the tool), and
     * Android 12+ kills apps' child processes beyond 32: all at once would cause false failures.
     */
    const val PROBE_PARALLEL = 4

    /** Worst-case time for the whole probe script, for the caller's timeout. */
    fun probeBudgetMs(count: Int, slowest: Int = PROBE_TIMEOUT_S): Long =
        ((count + PROBE_PARALLEL - 1) / PROBE_PARALLEL) * (slowest + 12) * 1000L + 10_000

    /**
     * One script that probes [tools], [PROBE_PARALLEL] at a time, and prints
     * `id<TAB>exit<TAB>first line` per tool. 127 = not installed, 124 = timed out.
     */
    fun probeScript(tools: List<Tool>): String = buildString {
        // Recreate the temp folder if it was deleted rather than failing every tool.
        appendLine("t=\"\${TMPDIR:-\$PREFIX/tmp}\"; mkdir -p \"\$t\" 2>/dev/null")
        appendLine("d=\$(mktemp -d \"\$t/probe.XXXXXX\") || exit 70")
        for ((i, tool) in tools.withIndex()) {
            // Each probe in its own subshell. -k: a tool that ignores SIGTERM is killed, so `wait` can't hang.
            // pipefail: "gh --version | sed -n 1p" must fail when gh is missing, not report sed's 0.
            append("( out=\$(timeout -k 5 ").append(tool.timeoutS).append(" bash -o pipefail -c ").append(shellQuote(tool.probe))
            append(" 2>&1 </dev/null); s=\$?; ")
            append("printf '%s\\t%s\\t%s\\n' ").append(shellQuote(tool.id)).append(" \"\$s\" \"\$(printf '%s' \"\$out\" | grep -m1 . | tr '\\t' ' ')\" > \"\$d/").append(i).append("\" ) &\n")
            if ((i + 1) % PROBE_PARALLEL == 0) appendLine("wait")
        }
        appendLine("wait")
        appendLine("cat \"\$d\"/* 2>/dev/null; rm -rf \"\$d\"")
    }

    /** Parses [probeScript] output; tools with no line (e.g. the shell died) count as not runnable. */
    fun parseProbe(output: String, tools: List<Tool>): List<ToolState> {
        val lines = output.lines().mapNotNull { line ->
            val f = line.split('\t', limit = 3)
            if (f.size >= 2) f[0] to (f[1].trim().toIntOrNull() to f.getOrElse(2) { "" }.trim()) else null
        }.toMap()
        return tools.map { tool ->
            val (code, text) = lines[tool.id] ?: (null to "")
            ToolState(tool, code ?: 126, text)
        }
    }

    /** Sourced by the setup scripts: the package lists and SDK versions above, as Bash. */
    fun shellManifest(): String = buildString {
        appendLine("# Generated by the Yurlungr app from Toolchain.kt on every launch. Don't edit.")
        appendLine("YURLUNGR_DEV_PACKAGES=(${DEV_PACKAGES.joinToString(" ")})")
        appendLine("YURLUNGR_AGENT_PACKAGES=(${AGENT_PACKAGES.joinToString(" ")})")
        appendLine("YURLUNGR_AGENT_GLIBC_PACKAGES=(${AGENT_GLIBC_PACKAGES.joinToString(" ")})")
        appendLine("YURLUNGR_SDK_PACKAGES=(${SDK_PACKAGES.joinToString(" ")})")
        // Optional agents (installed one by one from the app); the agents setup updates those present.
        appendLine("YURLUNGR_OPTIONAL_AGENT_PACKAGES=(${dev.yurlungr.app.agents.Agent.entries.mapNotNull { it.npmPackage }.joinToString(" ")})")
        for (setup in ToolSetup.entries.filter { it.optional }) {
            appendLine("YURLUNGR_PACKAGES_${setup.name.uppercase()}=(${setup.packages.joinToString(" ")})")
            appendLine("YURLUNGR_SPACE_MB_${setup.name.uppercase()}=${setup.spaceMb}")
        }
        appendLine("YURLUNGR_SDK_PLATFORM=$SDK_PLATFORM")
        appendLine("YURLUNGR_SDK_BUILD_TOOLS=$SDK_BUILD_TOOLS")
        // "name<TAB>command" per tool and setup, for the scripts' final version checks.
        for (setup in ToolSetup.entries) {
            val name = "YURLUNGR_CHECKS_" + setup.name.uppercase()
            appendLine("$name=(")
            tools.filter { it.setup == setup }.forEach { appendLine("  ${shellQuote(it.id + "\t" + it.probe)}") }
            appendLine(")")
        }
        // Tools checked only when installed (optional agents): the file that says so, under $PREFIX.
        // Global: the scripts source this file from inside a function.
        appendLine("declare -gA YURLUNGR_ONLY_IF=(")
        tools.filter { it.onlyIf != null }.forEach { appendLine("  [${shellQuote(it.id)}]=${shellQuote(it.onlyIf!!)}") }
        appendLine(")")
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
