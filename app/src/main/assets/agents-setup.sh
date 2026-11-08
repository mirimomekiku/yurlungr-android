#!/usr/bin/env bash
# Installs or updates the coding agents Yurlungr chats with, using the recipes verified
# in the Phase 0 spike:
#   Gemini CLI  - npm, speaks ACP natively (gemini --acp)
#   Codex       - Termux's native `codex` package + @agentclientprotocol/codex-acp
#   Claude Code - official linux-arm64 glibc build, interpreter patched to Termux's glibc
#                 loader, + @agentclientprotocol/claude-agent-acp
#   Antigravity - Google's installer (native android_arm64 build), ACP via `agy acp`
# Safe to re-run; it is also the update path.
set -u

LIB="$PREFIX/libexec/yurlungr"
LOG="$HOME/agents-setup.log"
APT_OPTS=(-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confnew)
export DEBIAN_FRONTEND=noninteractive
FAILED=()

COLS=$(stty size 2>/dev/null | cut -d' ' -f2)
COLS=${COLS:-80}

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m  ✓ %s\033[0m\n' "$*"; }
bad()  { printf '\033[31m  ✗ %s\033[0m\n' "$*"; FAILED+=("$1"); }

# Shows the latest output line in place; full output goes to $LOG.
run_logged() {
  "$@" 2>&1 | tee -a "$LOG" | while IFS= read -r line; do
    line=${line##*$'\r'}
    [ -n "$line" ] && printf '\r\033[K\033[2m  %s\033[0m' "${line:0:$((COLS - 3))}"
  done
  local status=${PIPESTATUS[0]}
  printf '\r\033[K'
  return "$status"
}

: > "$LOG"
mkdir -p "$LIB" "$HOME/projects/scratch"

step "System packages"
run_logged apt-get update || true
# npm's codex can't run on Android; Termux's native package replaces it.
npm uninstall -g @openai/codex >/dev/null 2>&1 || true
for p in nodejs-lts git gh ripgrep patchelf glibc-repo; do
  run_logged apt-get install "${APT_OPTS[@]}" "$p" && ok "$p" || bad "$p"
done
run_logged apt-get update || true
for p in glibc-runner codex; do
  run_logged apt-get install "${APT_OPTS[@]}" "$p" && ok "$p" || bad "$p"
done

step "Agent CLIs and ACP adapters (npm)"
for p in @google/gemini-cli @agentclientprotocol/codex-acp @agentclientprotocol/claude-agent-acp; do
  run_logged npm install -g --loglevel=http "$p@latest" && ok "$p" || bad "$p"
done
# Claude Code's Android build isn't published; force the linux-arm64 glibc package.
run_logged npm install -g --loglevel=http --os=linux --cpu=arm64 --libc=glibc @anthropic-ai/claude-code@latest \
  && ok "@anthropic-ai/claude-code" || bad "@anthropic-ai/claude-code"

step "Antigravity CLI (agy)"
# Google's installer picks the native android_arm64 (Bionic) build on Termux and verifies its
# SHA-512. Once installed, agy updates itself in the background, so it's skipped here.
if [ -x "$PREFIX/bin/agy" ]; then
  ok "already installed (self-updating)"
elif [ "$(uname -m)" != "aarch64" ]; then
  bad "agy: only published for 64-bit ARM Android"
else
  run_logged bash -c 'curl -fsSL https://antigravity.google/cli/install.sh | bash -s -- --dir "$PREFIX/bin"' \
    && [ -x "$PREFIX/bin/agy" ] && ok "installed" || bad "agy"
fi

step "Claude Code: glibc loader patch"
SRC="$(npm root -g)/@anthropic-ai/claude-code/node_modules/@anthropic-ai/claude-code-linux-arm64/claude"
GLIBC_LD="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
if [ -f "$SRC" ] && [ -f "$GLIBC_LD" ]; then
  cp "$SRC" "$LIB/claude-glibc.new" \
    && patchelf --set-interpreter "$GLIBC_LD" "$LIB/claude-glibc.new" \
    && mv "$LIB/claude-glibc.new" "$LIB/claude-glibc" && ok "patched" || bad "claude patch"
else
  bad "claude binary or glibc loader missing"
fi
cat > "$LIB/claude" <<WRAP
#!$PREFIX/bin/sh
# Claude Code ships a glibc build. Run it via Termux's glibc loader without the bionic
# termux-exec preload; exec through /system/bin/env so no preload hook intercepts it.
# Updates come from agents-setup.sh, not Claude's self-updater.
unset LD_PRELOAD
export DISABLE_AUTOUPDATER=1
exec /system/bin/env "$LIB/claude-glibc" "\$@"
WRAP
chmod 700 "$LIB/claude"
ln -sf "$LIB/claude" "$PREFIX/bin/claude"

step "Versions"
hash -r
check() { local v; if v=$(timeout 60 bash -c "$2" 2>&1 | grep -m1 .); then ok "$1: $v"; else bad "$1"; fi; }
check gemini 'gemini --version'
check codex  'codex --version'
check claude 'claude --version'
check agy    'agy --version 2>/dev/null'
check codex-acp 'command -v codex-acp'
check claude-agent-acp 'command -v claude-agent-acp'

echo
if [ ${#FAILED[@]} -eq 0 ]; then
  printf '\033[1;32mAll agents ready.\033[0m Sign in from the app: ☰ → Agents → Sign in.\n'
else
  printf '\033[1;31mFailed: %s\033[0m (details: %s)\n' "${FAILED[*]}" "$LOG"
fi
