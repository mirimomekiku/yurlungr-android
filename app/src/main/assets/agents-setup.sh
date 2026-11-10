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
# Node, the agent CLIs, glibc and Claude's binary together need roughly this much.
require_space 1500

step "System packages"
# npm's codex can't run on Android; Termux's native package replaces it.
npm uninstall -g @openai/codex >/dev/null 2>&1 || true
if apt_prepare; then
  # The developer tools (git, gh, Node, termux-exec…) and the agents' own packages.
  install_pkgs "${YURLUNGR_DEV_PACKAGES[@]}" "${YURLUNGR_AGENT_PACKAGES[@]}"
  # glibc-repo just added a repository; its packages need a fresh index.
  if run_logged apt-get "${APT_LOCK[@]}" update; then
    install_pkgs "${YURLUNGR_AGENT_GLIBC_PACKAGES[@]}"
  else
    bad "glibc package index update"
  fi
fi

step "Agent CLIs and ACP adapters (npm)"
# Bounded: a stalled registry download would otherwise hang the setup with no output.
for p in @google/gemini-cli @agentclientprotocol/codex-acp @agentclientprotocol/claude-agent-acp; do
  run_logged with_timeout 1800 npm install -g --loglevel=http "$p@latest" && ok "$p" || bad "$p"
done
# $PREFIX/bin/claude is our wrapper (below), not npm's link: npm refuses to replace a bin link
# that points outside its package (EEXIST), which would fail every rerun. Let npm put its own
# link back; the wrapper replaces it again after patching.
if [ -L "$PREFIX/bin/claude" ] && [ "$(readlink "$PREFIX/bin/claude")" = "$LIB/claude" ]; then
  rm -f "$PREFIX/bin/claude"
fi
# Claude Code's Android build isn't published; force the linux-arm64 glibc package.
run_logged with_timeout 1800 npm install -g --loglevel=http --os=linux --cpu=arm64 --libc=glibc @anthropic-ai/claude-code@latest \
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
  # -sS: no progress meter (it only redraws with \r, which shows nothing here); the speed limit
  # gives up on a stalled download instead of waiting forever.
  if run_logged curl -fsSL --retry 3 --connect-timeout 20 --speed-limit 1024 --speed-time 60 -o "$installer" https://antigravity.google/cli/install.sh \
    && bash -n "$installer" && run_logged with_timeout 1800 bash "$installer" --dir "$PREFIX/bin" \
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
# Only expose `claude` once there's a patched binary behind it; a wrapper to nothing would
# make Claude look installed while every chat fails to start.
if [ -x "$LIB/claude-glibc" ]; then
  # Written to a temp file and renamed: a full disk must not leave a truncated wrapper in place.
  cat > "$LIB/claude.new" <<WRAP
#!$PREFIX/bin/sh
# Claude Code ships a glibc build. Run it via Termux's glibc loader without the bionic
# termux-exec preload; exec through /system/bin/env so no preload hook intercepts it.
# Updates come from agents-setup.sh, not Claude's self-updater.
unset LD_PRELOAD
export DISABLE_AUTOUPDATER=1
exec /system/bin/env "$LIB/claude-glibc" "\$@"
WRAP
  [ $? -eq 0 ] && chmod 700 "$LIB/claude.new" && mv -f "$LIB/claude.new" "$LIB/claude" \
    && ln -sf "$LIB/claude" "$PREFIX/bin/claude" || { rm -f "$LIB/claude.new"; bad "claude wrapper"; }
fi

step "Versions"
hash -r
# The same checks System checks runs (toolchain.sh is generated from the app's list).
run_checks "${YURLUNGR_CHECKS_DEVTOOLS[@]}" "${YURLUNGR_CHECKS_AGENTS[@]}"

setup_finish "All agents ready. Sign in from the app: ☰ → Agents → Sign in."
