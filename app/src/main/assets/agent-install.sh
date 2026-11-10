#!/usr/bin/env bash
# Installs or updates one optional agent that ships as a native Linux binary:
#
#   bash agent-install.sh copilot    GitHub Copilot CLI  (Node single-file build, glibc)
#   bash agent-install.sh goose      Goose               (Rust, glibc)
#   bash agent-install.sh opencode   OpenCode            (Bun single-file build, glibc)
#
# Android has no glibc, so each runs on Termux's glibc (glibc-runner), like Claude Code. The
# static/musl builds were checked and aren't usable: musl ones need a loader Android lacks, and the
# static Goose build looks up DNS in /etc/resolv.conf, which Android doesn't have. Bun builds read
# their own executable, so OpenCode's interpreter is patched; the others are started through the
# glibc loader and left untouched (patching a Node single-file binary can damage its payload).
# Safe to re-run; it is also the update path.
set -u
AGENT="${1:-}"
case "$AGENT" in
  copilot|goose|opencode) ;;
  *) printf 'usage: agent-install.sh copilot|goose|opencode\n' >&2; exit 64 ;;
esac
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/agent-install-$AGENT.log"

LIB="$PREFIX/libexec/yurlungr"
DEST="$LIB/$AGENT"
GLIBC_LD="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
DL_OPTS=(-fsSL --retry 3 --connect-timeout 20 --speed-limit 1024 --speed-time 60)

if [ "$(uname -m)" != "aarch64" ]; then
  bad "$AGENT: only set up for 64-bit ARM phones"
  setup_finish ""
fi
require_space 800

step "glibc runtime (runs Linux glibc programs on Android)"
if [ -x "$GLIBC_LD" ] && command -v patchelf >/dev/null 2>&1; then
  ok "already installed"
elif apt_prepare; then
  install_pkgs patchelf glibc-repo
  if run_logged apt-get "${APT_LOCK[@]}" update; then install_pkgs glibc-runner; else bad "glibc package index update"; fi
fi
[ -x "$GLIBC_LD" ] || { bad "glibc loader missing ($GLIBC_LD)"; setup_finish ""; }

WORK=$(mktemp -d "$PREFIX/tmp/agent-install.XXXXXX") || { bad "temporary folder"; setup_finish ""; }

step "Download"
case "$AGENT" in
  copilot)
    # The npm package's loader can't pick a build on Android; fetch the linux-arm64 one directly.
    meta=$(curl "${DL_OPTS[@]}" https://registry.npmjs.org/@github/copilot-linux-arm64/latest) || { bad "Copilot release info"; setup_finish ""; }
    URL=$(printf '%s' "$meta" | grep -o '"tarball":"[^"]*"' | head -n1 | cut -d'"' -f4)
    ;;
  goose) URL="https://github.com/aaif-goose/goose/releases/latest/download/goose-aarch64-unknown-linux-gnu.tar.gz" ;;
  opencode) URL="https://github.com/sst/opencode/releases/latest/download/opencode-linux-arm64.tar.gz" ;;
esac
[ -n "${URL:-}" ] || { bad "download address"; setup_finish ""; }
if run_logged curl "${DL_OPTS[@]}" -o "$WORK/pkg.tgz" "$URL" && run_logged tar -xzf "$WORK/pkg.tgz" -C "$WORK"; then
  ok "downloaded $(basename "$URL")"
else
  bad "download or unpack ($URL)"
  rm -rf "$WORK"; setup_finish ""
fi

step "Install"
case "$AGENT" in
  copilot) BIN_SRC="$WORK/package/copilot" ;;
  goose) BIN_SRC=$(find "$WORK" -type f -name goose | head -n1) ;;
  opencode) BIN_SRC=$(find "$WORK" -type f -name opencode | head -n1) ;;
esac
if [ -z "${BIN_SRC:-}" ] || [ ! -f "$BIN_SRC" ]; then
  bad "the archive has no $AGENT program"
  rm -rf "$WORK"; setup_finish ""
fi
mkdir -p "$DEST" || exit 73
# Build the new copy beside the old one and swap it in, so a failure leaves the working version.
cp "$BIN_SRC" "$DEST/$AGENT.new" && chmod 700 "$DEST/$AGENT.new" || { bad "copy"; rm -rf "$WORK"; setup_finish ""; }
if [ "$AGENT" = opencode ]; then
  run_logged patchelf --set-interpreter "$GLIBC_LD" "$DEST/$AGENT.new" || { bad "patch interpreter"; rm -f "$DEST/$AGENT.new"; rm -rf "$WORK"; setup_finish ""; }
  RUN="exec /system/bin/env \"$DEST/$AGENT\" \"\$@\""
else
  RUN="exec /system/bin/env \"$GLIBC_LD\" \"$DEST/$AGENT\" \"\$@\""
fi
mv -f "$DEST/$AGENT.new" "$DEST/$AGENT" && ok "$DEST/$AGENT" || bad "install"
rm -rf "$WORK"

# The command on PATH: a wrapper that leaves the Bionic preload out of the glibc program. Goose's
# goes ahead of $PREFIX/bin (Termux's own "goose" package is an unrelated database tool).
case "$AGENT" in
  goose) WRAPPER="$LIB/bin/goose" ;;
  *) WRAPPER="$PREFIX/bin/$AGENT" ;;
esac
mkdir -p "$(dirname "$WRAPPER")" || exit 73
cat > "$WRAPPER.new" <<WRAP
#!$PREFIX/bin/sh
# $AGENT runs on Termux's glibc. Installed and updated by Yurlungr (agent-install.sh $AGENT).
unset LD_PRELOAD
$RUN
WRAP
[ $? -eq 0 ] && chmod 700 "$WRAPPER.new" && mv -f "$WRAPPER.new" "$WRAPPER" && ok "command: $WRAPPER" \
  || { rm -f "$WRAPPER.new"; bad "wrapper"; }

step "Version"
hash -r
for entry in "${YURLUNGR_CHECKS_AGENTS[@]}"; do
  [ "${entry%%$'\t'*}" = "$AGENT" ] && check "$AGENT" "${entry#*$'\t'}"
done

setup_finish "$AGENT is ready. Sign in from the app: ☰ → Agents."
