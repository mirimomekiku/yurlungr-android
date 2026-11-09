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
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/agents-setup.log"

LIB="$PREFIX/libexec/yurlungr"
mkdir -p "$LIB" "$HOME/projects/scratch" || exit 73

step "System packages"
run_logged apt-get update || bad "package index update"
# npm's codex can't run on Android; Termux's native package replaces it.
npm uninstall -g @openai/codex >/dev/null 2>&1 || true
for p in nodejs-lts git gh ripgrep patchelf curl glibc-repo; do
  run_logged apt-get install "${APT_OPTS[@]}" "$p" && ok "$p" || bad "$p"
done
run_logged apt-get update || bad "glibc package index update"
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
  installer=$(mktemp "$PREFIX/tmp/agy-install.XXXXXX") || exit 73
  if run_logged curl -fL --retry 3 --connect-timeout 20 -o "$installer" https://antigravity.google/cli/install.sh \
    && bash -n "$installer" && run_logged bash "$installer" --dir "$PREFIX/bin" \
    && [ -x "$PREFIX/bin/agy" ]; then ok "installed"; else bad "agy"; fi
  rm -f "$installer"
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
check gemini 'gemini --version'
check codex  'codex --version'
check claude 'claude --version'
check agy    'agy --version 2>/dev/null'
check codex-acp 'command -v codex-acp'
check claude-agent-acp 'command -v claude-agent-acp'

setup_finish "All agents ready. Sign in from the app: ☰ → Agents → Sign in."
