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
- **Staying alive.** `runtime/RuntimeService.kt` is a foreground service that keeps agents running (with a
  wake lock) while the app is in the background; `chat/ChatSessions.kt` notifies when an agent finishes or
  needs approval.

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
- `chat/ChatSessions.kt` is the app-wide registry; saved chats cost nothing until opened.
- `chat/ChatModel.kt` is the interface the chat screen draws, implemented by local sessions and by remote
  chats mirrored from another phone.

## Projects, editor, terminal

- `projects/Git.kt` runs the runtime's `git` and `gh` with `GIT_TERMINAL_PROMPT=0` and parses their output
  (porcelain v1 `-z` status, `for-each-ref`, `log`, `gh repo list` JSON); parsers are unit-tested.
  `GitGraph.kt` lays out commit lanes for `HistoryView.kt`. Interactive git work (clone, sign-in, publish)
  runs in a terminal session instead.
- `editor/` is self-contained: `Highlighter` (a linear, pure-Kotlin lexer), `EditorThemes`, `Formatter`
  (Prettier via `npx` in the runtime) and `EditorScreen`.
- `terminal/` wraps Termux's `terminal-view`; `TerminalSessions` owns the sessions across screens.

## Two phones

`remote/` lets one phone use another's agents. The host runs a WebSocket server (Java-WebSocket, port
8765, Tailscale address only unless local Wi-Fi is allowed) that authenticates clients with a pairing code
compared in constant time. The protocol (`RemoteProtocol.kt`) sends a snapshot when a chat is opened, then
per-item updates and text deltas from `ChatListener` events. The client (`RemoteClient.kt`) mirrors chats
as `ChatModel`s and reconnects with backoff unless the host disconnected it.

## Navigation

`MainActivity.kt` defines the `Route`s and a small `Navigator`: sidebar places replace the stack (☰
shown), drilling in pushes (‹ shown), and back closes the sidebar, then pops, then returns to New chat.
Transitions come from `ui/Motion.kt`.

## Source map

| Path (`app/src/main/java/dev/yurlungr/app/`) | What it does |
|---|---|
| `runtime/` | Bootstrap installer, environment, command runner, foreground service, URL / install bridge |
| `acp/` | JSON-RPC 2.0 over the agent's stdio |
| `chat/` | Chat sessions, persistence, attachments, the chat and new-chat screens, the composer |
| `agents/` | Agent launch recipes, install / sign-in detection, logos |
| `home/` | The Agents screen |
| `projects/` | Git operations, the workspace, history graph, branches, project list |
| `editor/` | Lexer, themes, Prettier formatting, the editor screen |
| `terminal/` | Terminal sessions and screen |
| `remote/` | Phone-to-phone protocol, server and client |
| `setup/` | System checks (first-run screen) and Settings |
| `ui/` | Theme, shared components, motion, loaders, icons, sidebar |
| `MainActivity.kt` | Routes and navigation |

`app/src/main/assets/` holds the runtime scripts (`agents-setup.sh`, `android-sdk-setup.sh`,
`spike-test.sh`) and the bundled licences.
