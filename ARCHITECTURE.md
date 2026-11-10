# Architecture

Yurlungr is a single-activity Jetpack Compose app that carries its own Linux userland and talks to coding
agents running inside it.

## Runtime

- **Bootstrap.** On first run the app downloads the official Termux bootstrap and unpacks it into
  `/data/data/com.termux/files/usr` (`runtime/BootstrapInstaller.kt`). The application ID is `com.termux`
  because Termux's prebuilt packages are compiled for that prefix; `targetSdk` is 28 because newer targets
  forbid running binaries from app data.
- **Environment.** `runtime/RuntimeEnv.kt` builds `PATH`, `HOME`, `PREFIX`, `LD_PRELOAD` (termux-exec) and
  friends for every process; `runtime/CommandRunner.kt` runs non-interactive commands with it.
- **Bridges.** Android's `am` is unusable from an app UID, so `runtime/RuntimeAssets.kt` installs shims
  (`xdg-open`, `termux-open-url`, `yurlungr-install`) that drop requests into an inbox folder;
  `runtime/RuntimeBridge.kt` watches it and opens URLs (http/https only) or the package installer.
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
ChatScreen ── ChatSession ── AcpConnection ── stdio ── agent CLI (claude-agent-acp, codex-acp, gemini --acp, agy acp)
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
- `chat/Markdown.kt` renders CommonMark trees with table/strikethrough extensions and local-file navigation.
- `chat/ChatSessions.kt` is the app-wide registry; saved chats cost nothing until opened.
- `chat/ChatModel.kt` is the interface the chat screen draws, implemented by local sessions and by remote
  chats mirrored from another phone.

## Projects, editor, terminal

- `projects/Git.kt` runs the runtime's `git` and `gh` with `GIT_TERMINAL_PROMPT=0` and parses their output
  (porcelain v1 `-z` status, `for-each-ref`, `log`, `gh repo list` JSON); parsers are unit-tested.
  `GitGraph.kt` lays out commit lanes for `HistoryView.kt`. Interactive git work (clone, sign-in, publish)
  runs in a terminal session instead.
- `projects/FileTreeGitState.kt` filters file views from Git's own ignored-path output and working-tree
  changes; the Uncommitted view includes deleted paths.
- `editor/` contains a Markdown preview with an explicit Edit route, plus the text editor: `Highlighter` (a linear, pure-Kotlin lexer), `EditorThemes`, `Formatter`
  (Prettier via `npx` in the runtime) and `EditorScreen`.
- `terminal/` wraps Termux's `terminal-view`; `TerminalSessions` owns sessions across screens. Long-press
  actions and confirmations use bottom sheets, while extra-key visibility is saved independently.
  Managed lifecycle callbacks let web supervisors handle End/Close/Restart and cleanup before a tab is removed.

## Local web runs

`web/ProjectDetector.kt` inspects bounded project folders for Vite scripts or static entry points.
`WebRunUi.kt` configures and controls runs; `WebRunSessions.kt` owns one run per project. Each run starts
`assets/web-runner.cjs` in a managed terminal without an idle-shell fallback. The Node supervisor owns a
loopback HTTP listener, serves static files or proxies Vite HTTP/HMR, writes per-run atomic status, and
stops its child process group on request. Browser readiness checks include a unique run ID. Android's
network security configuration permits cleartext health checks only for loopback hosts.

Terminal lifecycle hooks route End/Close/Restart through the supervisor. A process exit changes run
state even if its terminal remains visible. The registry is process-local; startup asks stale supervisors
to stop rather than adopting saved PIDs. The runtime foreground service remains responsible for keeping
terminal processes alive.

## Two phones

`remote/` lets one phone use another's agents. The host runs a WebSocket server (Java-WebSocket, port
8765, Tailscale address only unless local Wi-Fi is allowed, which still refuses peers outside Tailscale and
private LAN ranges). Pairing (`RemotePairing.kt`) is a mutual HMAC challenge-response over the pairing code,
so the code is never sent and the client also verifies the host; afterwards every frame is sealed with
AES-256-GCM under per-direction keys derived from the code and both nonces (counter nonces reject replay
and reordering). Unpaired connections get 10 s and 4 KB messages; frames are capped at 64 MB. The protocol (`RemoteProtocol.kt`) sends a snapshot when a chat is opened, then
per-item updates and text deltas from `ChatListener` events. The client (`RemoteClient.kt`) mirrors chats
as `ChatModel`s and reconnects with backoff unless the host disconnected it.

## Navigation

`MainActivity.kt` defines the `Route`s and a small `Navigator`: sidebar places replace the stack (☰
shown), drilling in pushes (‹ shown), and back closes the sidebar, then pops, then returns to New chat.
Transitions come from `ui/Motion.kt`.

## Source map

| Path (`app/src/main/java/dev/yurlungr/app/`) | What it does |
|---|---|
| `runtime/` | Bootstrap, environment, command runner, foreground service, bridges and MOTD status |
| `acp/` | JSON-RPC 2.0 over the agent's stdio |
| `chat/` | Chat sessions, persistence, attachments, the chat and new-chat screens, the composer |
| `agents/` | Agent launch recipes, install / sign-in detection, logos |
| `home/` | The Agents screen |
| `projects/` | Git operations, the workspace, history graph, branches, project list |
| `editor/` | Markdown viewer, lexer, themes, Prettier formatting and text editor |
| `terminal/` | Terminal sessions and screen |
| `web/` | Project detection, saved run configuration, supervisor registry and Run UI |
| `remote/` | Phone-to-phone protocol, server and client |
| `setup/` | System checks (first-run screen) and Settings |
| `ui/` | Theme, shared components, motion, loaders, icons, sidebar |
| `MainActivity.kt` | Routes and navigation |

`app/src/main/assets/` holds the runtime scripts (`agents-setup.sh`, `android-sdk-setup.sh`,
`spike-test.sh`, `motd.sh`, `web-runner.cjs`) and the bundled licences.
