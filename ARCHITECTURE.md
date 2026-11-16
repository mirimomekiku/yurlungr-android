# Architecture

Yurlungr is a single-activity Jetpack Compose app that carries its own Linux userland and talks to coding
agents running inside it.

## Runtime

- **Bootstrap.** On first run the app downloads the official Termux bootstrap and unpacks it into
  `/data/data/com.termux/files/usr` (`runtime/BootstrapInstaller.kt`). The application ID is `com.termux`
  because Termux's prebuilt packages are compiled for that prefix; `targetSdk` is 28 because newer targets
  forbid running binaries from app data.
- **Environment.** `runtime/RuntimeEnv.kt` builds the environment for every process (agents, terminals,
  setups, web runs, `runtime/CommandRunner.kt`):
  - `HOME`, `PREFIX` and `LD_PRELOAD` (termux-exec);
  - `PATH`: the app's shims, `$PREFIX/bin`, then the per-user tool folders (`~/.local/bin`, `~/.cargo/bin`,
    `~/go/bin`, `~/.bun/bin`…);
  - `JAVA_HOME` (the default JDK, from `runtime/Jdks.kt`), `ANDROID_HOME`, `PNPM_HOME`, `GOPATH`,
    `CARGO_HOME` and `DOTNET_ROOT`.

  Non-login shells (agents, command terminals, setups) also load `$PREFIX/etc/profile.d`, silently, so
  they match login terminals.
- **JDKs.** `runtime/Jdks.kt` lists the JDKs under `$PREFIX/lib/jvm` and finds the default through Debian
  alternatives (`update-alternatives --set java`, or `--auto` for the newest). It also keeps
  `~/.gradle/gradle.properties` listing every JDK (`org.gradle.java.installations.paths`, auto-download
  off), refreshed on launch and after a switch.
- **Bridges.** Android's `am` is unusable from an app UID, so `runtime/RuntimeAssets.kt` installs shims
  (`xdg-open`, `termux-open-url`, `yurlungr-install`, `yurlungr-notify`) that drop requests into an
  inbox folder. `runtime/RuntimeBridge.kt` watches it and does one of four things:
  - opens URLs (http/https only);
  - opens the package installer;
  - posts notifications;
  - handles `.done` reports, which terminal commands write when they ran 20 s or more
    (`runtime/TaskNotifications.kt`).
- **Storage.** `runtime/Storage.kt` reads pack sizes from dpkg's status file, measures and clears
  caches, and counts upgradable packages. `assets/pack-remove.sh` removes a pack's packages but keeps
  anything the core setups need.
- **Agent notes.** On every launch the app rewrites a managed "you're on an Android phone" block in each
  agent's instructions file (`~/.claude/CLAUDE.md`, `~/.codex/AGENTS.md`, `~/.gemini/GEMINI.md`).
- **Staying alive.** `runtime/RuntimeService.kt` is a foreground service that keeps agents and terminal processes running (with an
  optional user-controlled wake lock) while the app is in the background; `chat/ChatSessions.kt` notifies when an agent finishes or
  needs approval.

- **Terminal welcome.** `runtime/RuntimeStatus.kt` exports session counts and public agent/approval
  summaries. `assets/motd.sh` reads them through Termux's dynamic MOTD hook; `yurlungr-status` refreshes
  the display on demand. Existing custom hooks are preserved, and account hints check file presence only.

## Agents and chat

```
ChatScreen ── ChatSession ── AcpConnection ── stdio ── agent CLI (claude-agent-acp, codex-acp, gemini --acp, agy acp, qwen --acp…)
                  │
               ChatStore (one JSON file per chat in app_chats/)
```

- `agents/Agents.kt` holds each agent's launch command, extra environment, sign-in command and
  install / sign-in detection. `assets/agents-setup.sh` installs them (see the README's Agents table).
- `acp/AcpConnection.kt` is JSON-RPC 2.0 over the agent's stdio; incoming messages are handled in order on
  the main thread.
- `chat/ChatSession.kt` is one chat = one agent process: `initialize` → `session/new` (or `session/load`
  when reopening) → `session/prompt`. It renders `session/update` items, answers
  `session/request_permission`, serves `fs/read_text_file` / `fs/write_text_file`, and applies session
  options (`session/set_config_option`, modes).
- New-chat Skip permissions is stored with the chat. Before the first prompt, `ChatSession` selects an
  advertised bypass mode; Antigravity instead receives its documented launch flag. Remote creation
  forwards the same setting, and remote metadata includes its current state.
- Markdown rendering:
  - `chat/MarkdownModel.kt` parses GitHub-flavoured Markdown (tables, task lists, autolinks, footnotes,
    front matter) into top-level blocks. It groups `<details>` sections, gives headings GitHub-style
    anchors, and allowlists safe HTML (`HtmlLite`).
  - `chat/Markdown.kt` renders the blocks: code with the editor's lexer and theme, images (remote ones
    only after a tap), callouts, and find highlighting.
- `chat/ChatSessions.kt` is the app-wide registry; saved chats cost nothing until opened.
- `chat/ChatModel.kt` is the interface the chat screen draws, implemented by local sessions and by remote
  chats mirrored from another phone.

## Projects, editor, terminal

- `projects/Git.kt` runs the runtime's `git` and `gh` with `GIT_TERMINAL_PROMPT=0` and parses their output
  (porcelain v1 `-z` status, `for-each-ref`, `log`, `gh repo list` JSON); parsers are unit-tested.
  `GitGraph.kt` lays out commit lanes for `HistoryView.kt`. Interactive git work (clone, sign-in, publish)
  runs in a terminal session instead.
- `projects/FileTreeGitState.kt` filters file views from Git's own ignored-path output and working-tree
  changes (All, Changed, Source, Ignored). The Changed view includes deleted paths, and folders count the
  changes inside them.
- `projects/ProjectChanges.kt` is the Changes tab: Conflicts, Staged and Changes groups, stage, unstage
  and discard (`Git.stage/unstage/discard`), and a pinned commit box that commits staged files or all.
- `projects/ProjectChecks.kt` is the Checks tab. It works out check and test commands per stack and
  runs them in a terminal with `tee` into `.yurlungr/`. Parsers turn compiler and linter output,
  JUnit XML, Jest JSON and `go`/`cargo` test output into problems and test results.
- `editor/EditorTools.kt`: recent files, `git blame --line-porcelain` ranges, and "Find definition"
  (a ripgrep regex for definition-like lines). The editor opens at `Route.FileView.line`.
- `editor/Lsp.kt` is a small LSP client (Content-Length JSON-RPC over the server's stdio): diagnostics,
  definition, hover and completion. There's one server per project and language (`LspManager`), started
  with the runtime's environment and stopped when the last editor using it closes. `OpenFiles` keeps
  tabs and unsaved buffers; `MergeScreen.kt` parses conflict markers (diff3 bases included);
  `Snippets.kt` holds snippets and new-file templates; `DatabaseScreen.kt` opens SQLite with Android's
  `SQLiteDatabase`.
- `chat/Checkpoints.kt` snapshots a chat's project before and after each prompt into a shadow bare repository under
  `files/checkpoints/` (per-chat index file, `refs/chats/<id>/…`, its own excludes), and restores from one.
  Turn records double as usage statistics.
- `ui/Feedback.kt`: vibration, sounds, and the battery/heat check `MainActivity.runInTerminal` makes before
  heavy commands. Accent and pure black are in `ThemePrefs`/`AppTheme`. `ui/TourScreen.kt` is the first-run tour.
- `ui/CommandPalette.kt` (Search everything) ranks actions, projects, recent files, chats, and the
  last project's tasks and files (`rg --files`). It opens through `LocalOpenPalette`.
- `ui/ShareSheet.kt` takes `ACTION_SEND` / `SEND_MULTIPLE` into a project chat or folder.
  `widgets/Shortcuts.kt` holds the Quick Settings tiles and the home-screen widget; both open places
  through `MainActivity.EXTRA_OPEN`.
- `projects/ProjectTasks.kt` finds runnable tasks in a project's own files (scripts, make targets,
  just recipes, stack defaults). `ProjectSearch.kt` runs ripgrep (`--null` output). Both are
  reached from the dock and the Files tab (`ProjectDock.kt`, `ProjectFiles.kt`).
- `projects/ProjectAgentsScreen.kt` keeps per-project agent settings: the default agent (app
  preferences), the instruction files, and MCP servers. `.mcp.json` is the source of the servers;
  they're merged into `.gemini/settings.json` and `.qwen/settings.json` so other keys are kept.
- `projects/StackDetector.kt` finds a project's stacks from their manifests. For each stack it records
  the Termux packages it needs, the install steps, and whether dependencies are installed. It decides
  runtimes from `packageManager` and lockfiles, and adds node-gyp's toolchain for native add-ons.
  `ProjectSetupSheet.kt` shows each stack, its runtimes (installed or missing, from dpkg's file lists)
  and Install, which runs `assets/project-setup.sh` in a terminal. It also has the opt-in automatic
  setup (once per need).
- `editor/` contains the Markdown viewer (lazy blocks, find, outline, anchors, text size, live reload)
  with an explicit Edit route, plus the text editor: `Highlighter` (a linear, pure-Kotlin lexer), `EditorThemes`, `Formatter`
  (Prettier via `npx` in the runtime) and `EditorScreen`.
- `terminal/` wraps Termux's `terminal-view`; `TerminalSessions` owns sessions across screens. Long-press
  actions and confirmations use bottom sheets, while extra-key visibility is saved independently.
  Managed lifecycle callbacks let web supervisors handle End/Close/Restart and cleanup before a tab is removed.

## Local web runs

`web/ProjectDetector.kt` inspects bounded project folders for framework dependencies, development
scripts and static entry points. Framework identity, launch mode and runtime compatibility are separate.
Adapters cover Vite/SvelteKit, Next.js, Astro, Nuxt, Angular, Create React App, Expo web, Parcel and Webpack.
Package managers are npm, pnpm, Yarn and Bun. Project script mode preserves compound commands;
Framework CLI resolves installed tools and their supported flags instead of evaluating shell presets.

`PreviewScreen.kt` shows running apps in a WebView: only loopback addresses load inside it; other links
open in the browser. It also collects the page's console. `ServersScreen.kt` lists runs, probes common
ports (Android blocks `/proc/net` for apps) and lists runtime processes with Stop/Force kill.
`WebQuickRun.kt` restores saved modes/profiles/readiness options and picks a free port for the dock's
one-tap Run. The dock disables new attempts while a run is busy and becomes Open once it is running.
`WebRunUi.kt` provides the server card, preview, recovery actions and options; `WebRunSessions.kt` owns
one run per project. Each run starts
`assets/web-runner.cjs` in a managed terminal without an idle-shell fallback. The Node supervisor owns a
loopback HTTP listener, serves static files or proxies framework HTTP/HMR, writes per-run atomic status, and
stops its child process group on request. Browser readiness checks include a unique run ID. Android's
network security configuration permits cleartext health checks only for loopback hosts.

`assets/web-adapters.cjs` resolves installed framework CLIs and their version/engine requirements,
probes supported flags, and builds launch recipes. Framework mode bypasses package scripts; script
mode adds flags only to simple recognized launches, preserving compound commands. Custom mode gets
HOST/PORT without command rewriting. Next's compatibility profile selects Webpack where supported;
explicit repair downloads its exact WASM compiler via Next's downloader into generated dependencies.
Compatibility preflight tests installed Lightning CSS bindings and downloads only missing versions'
matching official WASM packages (install scripts disabled) into `cache/web-compat`. The inherited Node
preload redirects only those resolved module entries for the run, including its workers. Project
manifests, lockfiles and source are unchanged. Explicit Turbopack scripts get a targeted recovery action.
Run reservations remain busy until supervisor cleanup is finalized, and disable both entry-point buttons.
`web-node-preload.cjs` corrects Termux's linker execPath in forked Node workers without changing globals.
Readiness probes compile the selected route and require HTTP 200–399 within a configurable timeout.
Native compiler and setup failures are classified from bounded output; raw output remains in Logs.
The proxy preserves the public Host and forwards host/protocol/port for SSR and authentication.

Terminal lifecycle hooks route End/Close/Restart through the supervisor. A process exit changes run
state even if its terminal remains visible. The registry is process-local; startup asks stale supervisors
to stop rather than adopting saved PIDs. The runtime foreground service remains responsible for keeping
terminal processes alive.

## Two phones

`remote/` lets one phone use another's agents. The host runs a WebSocket server (Java-WebSocket, port
8765, Tailscale address only unless local Wi-Fi is allowed, which still refuses peers outside Tailscale and
the subnets of this phone's own Wi-Fi/Ethernet interfaces; cellular and VPN interfaces don't count). An address
that fails to pair is refused for a while (`PairingBackoff`, doubling to 5 minutes), and trusted devices
reconnect through their own pairing slots. Pairing codes and device secrets are stored encrypted with an
Android Keystore key (`SecretBox`). Pairing (`RemotePairing.kt`) is a mutual HMAC challenge-response over the pairing code,
so the code is never sent and the client also verifies the host; afterwards every frame is sealed with
AES-256-GCM under per-direction keys derived from the code and both nonces (counter nonces reject replay
and reordering). Unpaired connections get 10 s and 4 KB messages; frames are capped at 64 MB. The protocol (`RemoteProtocol.kt`) sends a snapshot when a chat is opened, then
per-item updates and text deltas from `ChatListener` events. The client (`RemoteClient.kt`) mirrors chats
as `ChatModel`s and reconnects with backoff unless the host disconnected it.

## Optional toolchains

`ToolSetup` entries marked `optional` (42 of them: Python, Build tools, Utilities, Editors, Go, Rust, Ruby,
JavaScript runtimes, Java & Kotlin, PHP, Dart, .NET, Databases, Swift, Zig, Elixir, Lua, Crystal, Nim, Haskell,
Scala, D, Perl, Shell, CLI tools, Servers, DevOps, Media, Local AI, Linux distros, Native libraries, Python
science, Debugging, Language servers, Networking, Security, Data tools, Task runners, Docs, Archives, OCR & PDF and
a headless browser) are separate packs.
Each has its own script in the runtime (`libexec/yurlungr/<pack>-setup.sh`, so its own log and status file), its own
Install button in System checks (one "(optional)" group each; a missing tool is Info, not a warning) and its own `Fix`.
The scripts are all one asset, `assets/pack-setup.sh` (`ToolSetup.asset`), which `RuntimeAssets` installs under each
pack's script name; it picks its pack from that name (`YURLUNGR_PACK_OF_SCRIPT` in the generated `toolchain.sh`) and
reads that pack's `YURLUNGR_PACKAGES_<PACK>`, space need, title and `YURLUNGR_CHECKS_<PACK>`. To add a pack: a
`ToolSetup` entry, its `Tool` probes and a `Fix` entry (with its branches in `SetupScreen`). `browser-setup.sh` is the one
custom script: it enables Termux's x11 repository first. The managed agent notes list the packs that are installed, refreshed on app launch.

Tools with `onlyIf` (optional agents) are checked only when installed, by System checks and by the scripts
(`YURLUNGR_ONLY_IF` in `toolchain.sh`). JVM tools have a longer probe time (`Tool.timeoutS`).
`setup/DevEnvironmentScreen.kt` (Settings → Development environment) shows several things:
- the JDK switcher and JDK installs;
- the Gradle toolchain list;
- the full environment, with copyable exports;
- every pack.

## Navigation

`MainActivity.kt` defines the `Route`s and a small `Navigator`:
- sidebar places (New chat, Projects, Terminal, Servers, Agents, Devices, Settings) replace the stack, with ☰ shown;
- drilling in pushes, with ‹ shown;
- editor tabs replace the top;
- back closes the sidebar, then pops, then returns to New chat.

It also handles intents from notifications, tiles, the widget, launcher shortcuts and shares
(`EXTRA_OPEN`, `EXTRA_TERMINAL_ID`, `ACTION_SEND`). On wide screens a file opened from a project shows
beside it.
Transitions come from `ui/Motion.kt`.

## Source map

| Path (`app/src/main/java/dev/yurlungr/app/`) | What it does |
|---|---|
| `runtime/` | Bootstrap, environment, command runner, foreground service, bridges and MOTD status |
| `acp/` | JSON-RPC 2.0 over the agent's stdio |
| `chat/` | Chat sessions, persistence, attachments, the chat and new-chat screens, the composer; approval rules (`ApprovalRules`), the artifacts sheet (`Artifacts`), the Activity screen (`ActivityScreen`), saved prompts (`PromptLibrary`) and search inside chats (`ChatSearch`) |
| `agents/` | Agent launch recipes, install / sign-in detection, logos |
| `home/` | The Agents screen |
| `projects/` | Git operations, the workspace, history graph, branches, project list |
| `editor/` | Markdown viewer, lexer, themes, Prettier formatting and text editor |
| `terminal/` | Terminal sessions and screen |
| `web/` | Project detection, saved run configuration, supervisor registry, Run UI, Preview and Servers |
| `remote/` | Phone-to-phone protocol, server and client |
| `setup/` | System checks (first-run screen), Settings and Development environment |
| `ui/` | Theme, shared components, motion, loaders, icons, sidebar |
| `widgets/` | Quick Settings tiles, the home-screen widget (with its quick-ask bar) and launcher shortcuts |
| `MainActivity.kt` | Routes and navigation |

`app/src/main/assets/` holds the runtime scripts (`agents-setup.sh`, `android-sdk-setup.sh`, `pack-setup.sh` for the optional packs plus the custom `browser-setup.sh`,
`spike-test.sh`, `motd.sh`, `web-runner.cjs`) and the bundled licences.
