#!/usr/bin/env bash
# Installs or updates one optional agent that isn't a plain npm package:
#
#   bash agent-install.sh <agent>
#
#   Native Linux builds (glibc), run on Termux's glibc like Claude Code:
#     copilot   GitHub Copilot CLI   (Node single-file build, from npm @github/copilot-linux-arm64)
#     goose     Goose                (Rust)
#     opencode  OpenCode             (Bun single-file build)
#     cline     Cline CLI            (Bun, from npm @cline/cli-linux-arm64)
#     kilo      Kilo Code CLI        (Bun, an OpenCode fork; with its tree-sitter files)
#     cursor    Cursor agent         (its own Node and scripts)
#     amp       Amp                  (the amp CLI, Bun, plus the amp-acp adapter)
#     vibe      Mistral Vibe ACP     (PyInstaller folder with its own Python)
#     kiro      Kiro CLI             (Rust)
#   Python packages (uv, on Termux's own Python):
#     fast-agent   fast-agent-acp
#     minion       minion-code (its `mcode` command is wrapped as `minion-code`: MiniMax's is `mcode` too)
#
# Android has no glibc, so native programs run on Termux's glibc (glibc-runner). Musl and static
# builds were tried before and aren't usable (musl needs a loader Android lacks; static Go/Rust
# builds look up DNS in /etc/resolv.conf, which Android doesn't have). Archives come from the ACP
# Registry (agentclientprotocol.com), so updates follow it. Every glibc program in a bundle gets
# Termux's glibc loader as its interpreter, except Node single-file builds, which are started
# through the loader instead (patching can damage their payload). Safe to re-run: it's the update path.
set -u
AGENT="${1:-}"
case "$AGENT" in
  copilot|goose|opencode|cline|kilo|cursor|amp|vibe|kiro|fast-agent|minion) ;;
  *) printf 'usage: agent-install.sh copilot|goose|opencode|cline|kilo|cursor|amp|vibe|kiro|fast-agent|minion\n' >&2; exit 64 ;;
esac
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/agent-install-$AGENT.log"

LIB="$PREFIX/libexec/yurlungr"
DEST="$LIB/$AGENT"
GLIBC_LD="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
# HTTPS only, redirects included: a redirect to http:// would let the network swap the download.
DL_OPTS=(-fsSL --proto =https --proto-redir =https --retry 3 --connect-timeout 20 --speed-limit 1024 --speed-time 60)
REGISTRY="https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"

# An update killed between moving the old copy aside and moving the new one in leaves only
# $DEST.old: that's the working install. Put it back first, so it works even if this run fails.
if [ ! -e "$DEST" ] && [ -e "$DEST.old" ]; then
  mv "$DEST.old" "$DEST" && ok "restored the previous $AGENT install (an earlier update was interrupted)"
fi

# A command on PATH that runs [target]; glibc programs leave the Bionic preload out.
write_wrapper() { # NAME BODY
  local wrapper="$PREFIX/bin/$1"
  [ "$AGENT" = goose ] && wrapper="$LIB/bin/goose" # Termux's own "goose" is an unrelated tool
  mkdir -p "$(dirname "$wrapper")" || exit 73
  if printf '#!%s/bin/sh\n# Installed and updated by Yurlungr (agent-install.sh %s).\n%s\n' "$PREFIX" "$AGENT" "$2" > "$wrapper.new" \
      && chmod 700 "$wrapper.new" && mv -f "$wrapper.new" "$wrapper"; then
    ok "command: $wrapper"
  else
    rm -f "$wrapper.new"; bad "wrapper $1"
  fi
}

finish_with_version() {
  step "Version"
  hash -r
  local entry
  for entry in "${YURLUNGR_CHECKS_AGENTS[@]}"; do
    case " $* " in *" ${entry%%$'\t'*} "*) check "${entry%%$'\t'*}" "${entry#*$'\t'}" ;; esac
  done
  setup_finish "$AGENT is ready. Sign in from the app: ☰ → Agents."
}

# ---- Python agents ---------------------------------------------------------------------------
case "$AGENT" in
  fast-agent|minion)
    require_space 2500
    step "Python, uv and build tools"
    # Their dependencies (pydantic-core and others) have no Android wheels: they compile, with Rust.
    apt_prepare && install_pkgs python uv rust clang make pkg-config binutils libffi openssl
    hash -r
    command -v uv >/dev/null || { bad "uv"; setup_finish ""; }
    step "Install (compiling can take 10–30 minutes the first time)"
    # Termux's Python: uv's downloadable ones are glibc builds and don't run here.
    export UV_PYTHON_PREFERENCE=only-system UV_PYTHON="$PREFIX/bin/python"
    case "$AGENT" in
      fast-agent) PKG=fast-agent-acp; EXE=fast-agent-acp; CMD=fast-agent-acp ;;
      minion) PKG=minion-code; EXE=mcode; CMD=minion-code ;;
    esac
    if run_logged with_timeout 3600 uv tool install --force "$PKG"; then ok "$PKG"; else bad "uv tool install $PKG"; setup_finish ""; fi
    # The program inside the tool's own environment; the wrapper points there.
    TOOL_EXE="$(uv tool dir 2>/dev/null || echo "$HOME/.local/share/uv/tools")/$PKG/bin/$EXE"
    [ -x "$TOOL_EXE" ] || { bad "$TOOL_EXE missing"; setup_finish ""; }
    # Minion's "mcode" link in ~/.local/bin would shadow MiniMax Code's "mcode" (earlier on PATH).
    [ "$AGENT" = minion ] && rm -f "$(uv tool dir --bin 2>/dev/null || echo "$HOME/.local/bin")/mcode"
    # fast-agent's provider key and model (agent-key.sh fast-agent).
    LOAD=""
    [ "$AGENT" = fast-agent ] && LOAD='[ -f "$HOME/.config/yurlungr/fast-agent.env" ] && . "$HOME/.config/yurlungr/fast-agent.env"
'
    write_wrapper "$CMD" "${LOAD}exec \"$TOOL_EXE\" \"\$@\""
    finish_with_version "$CMD"
    exit 0
    ;;
esac

# ---- Native agents ---------------------------------------------------------------------------
if [ "$(uname -m)" != "aarch64" ]; then
  bad "$AGENT: only set up for 64-bit ARM phones"
  setup_finish ""
fi
require_space 1500

step "glibc runtime (runs Linux glibc programs on Android)"
if [ -x "$GLIBC_LD" ] && command -v patchelf >/dev/null 2>&1; then
  ok "already installed"
elif apt_prepare; then
  install_pkgs patchelf glibc-repo
  if run_logged with_timeout 600 apt-get "${APT_LOCK[@]}" update; then install_pkgs glibc-runner; else bad "glibc package index update"; fi
fi
[ -x "$GLIBC_LD" ] || { bad "glibc loader missing ($GLIBC_LD)"; setup_finish ""; }

# Folders left by runs that were killed outright (no trap runs then). The setup lock is held, so
# no other install is using them.
rm -rf "$PREFIX"/tmp/agent-install.?????? 2>/dev/null
WORK=$(mktemp -d "$PREFIX/tmp/agent-install.XXXXXX") || { bad "temporary folder"; setup_finish ""; }
# Removed however the run ends (Ctrl-C, a closed tab, an error): setup-common's EXIT trap does it.
cleanup_on_exit "$WORK"
fail() { bad "$1"; setup_finish ""; }

need_node() { command -v node >/dev/null || { echo "Node.js is needed to $1: install the developer tools" >&2; return 1; }; }

# The functions below print "URL CHECKSUM": the checksum ("sha512-<base64>", "sha256:<hex>" or
# "sha1:<hex>") is left out where the source publishes none.

# The linux-aarch64 archive the ACP Registry lists for agent [id], with its sha256 if listed.
registry_url() {
  need_node "read the agent registry" || return 1
  curl "${DL_OPTS[@]}" "$REGISTRY" | node -e '
    let s = ""; process.stdin.on("data", c => s += c).on("end", () => {
      const j = JSON.parse(s); const list = Array.isArray(j) ? j : (j.agents || []);
      const a = list.find(x => x.id === process.argv[1]);
      const b = a && a.distribution && a.distribution.binary && a.distribution.binary["linux-aarch64"];
      if (!b) process.exit(1); process.stdout.write(b.archive + (b.sha256 ? " sha256:" + b.sha256 : ""));
    });' "$1"
}
# The tarball of the latest npm package [name], with the integrity npm lists for it.
npm_url() {
  need_node "read npm package details" || return 1
  curl "${DL_OPTS[@]}" "https://registry.npmjs.org/$(printf '%s' "$1" | sed 's#/#%2F#')/latest" | node -e '
    let s = ""; process.stdin.on("data", c => s += c).on("end", () => {
      const d = JSON.parse(s).dist || {};
      const sri = (d.integrity || "").split(/\s+/).find(x => x.startsWith("sha512-"));
      const sum = sri || (d.shasum ? "sha1:" + d.shasum : "");
      if (!d.tarball || !sum) process.exit(1); process.stdout.write(d.tarball + " " + sum);
    });'
}
# Asset [name] of GitHub repository [owner/repo]'s latest release, with the sha256 GitHub lists
# for it. Without Node or the API (it's rate-limited), the plain latest-release link, unchecked.
github_url() { # OWNER/REPO ASSET
  command -v node >/dev/null && curl "${DL_OPTS[@]}" -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$1/releases/latest" | node -e '
    let s = ""; process.stdin.on("data", c => s += c).on("end", () => {
      const a = (JSON.parse(s).assets || []).find(x => x.name === process.argv[1]);
      if (!a || !a.browser_download_url) process.exit(1);
      process.stdout.write(a.browser_download_url + (/^sha256:[0-9a-f]{64}$/.test(a.digest || "") ? " " + a.digest : ""));
    });' "$2" 2>/dev/null && return 0
  warn "GitHub's API didn't answer (it's rate-limited), so $2 comes from the plain latest-release link"
  printf 'https://github.com/%s/releases/latest/download/%s' "$1" "$2"
}
# Checks [file] against [checksum] (formats above).
verify_sum() { # FILE CHECKSUM
  node -e '
    const [file, sum] = process.argv.slice(1);
    const m = /^(sha512)-([A-Za-z0-9+\/]+=*)$/.exec(sum) || /^(sha256|sha1):([0-9a-fA-F]+)$/.exec(sum);
    if (!m) { console.error("Unknown checksum format: " + sum); process.exit(2); }
    const b64 = m[1] === "sha512";
    const got = require("crypto").createHash(m[1]).update(require("fs").readFileSync(file)).digest(b64 ? "base64" : "hex");
    if (got !== (b64 ? m[2] : m[2].toLowerCase())) { console.error("Checksum mismatch: expected " + sum + ", got " + got); process.exit(1); }
    console.log("checksum ok (" + m[1] + ")");
  ' "$1" "$2"
}
# Downloads [url], checks it against [checksum] if there is one, and unpacks it into [dir].
fetch() { # URL DIR [CHECKSUM]
  mkdir -p "$2" || return 1
  local file="$WORK/$(basename "${1%%\?*}")"
  run_logged curl "${DL_OPTS[@]}" -o "$file" "$1" || return 1
  if [ -n "${3:-}" ]; then
    run_logged verify_sum "$file" "$3" || return 1
  else
    warn "No published checksum for $(basename "${1%%\?*}"): relying on HTTPS alone"
  fi
  case "$file" in
    *.zip) run_logged unzip -q -o "$file" -d "$2" ;;
    *) run_logged tar -xzf "$file" -C "$2" ;;
  esac
}

step "Download"
NEW="$WORK/new"
case "$AGENT" in
  copilot) URLS=("$(npm_url @github/copilot-linux-arm64)") ;;
  goose) URLS=("$(github_url aaif-goose/goose goose-aarch64-unknown-linux-gnu.tar.gz)") ;;
  opencode) URLS=("$(github_url sst/opencode opencode-linux-arm64.tar.gz)") ;;
  cline) URLS=("$(npm_url @cline/cli-linux-arm64)") ;;
  kilo) URLS=("$(registry_url kilo)") ;;
  cursor) URLS=("$(registry_url cursor)") ;;
  vibe) URLS=("$(registry_url mistral-vibe)") ;;
  kiro) URLS=("$(registry_url kiro)") ;;
  amp) URLS=("$(registry_url amp-acp)" "$(npm_url @ampcode/cli-linux-arm64)") ;;
esac
i=0
for entry in "${URLS[@]}"; do
  read -r url sum <<< "$entry"
  [ -n "$url" ] || fail "download address (registry or npm unreachable?)"
  # Amp: the adapter and the CLI go in their own folders.
  dir="$NEW"; [ "$AGENT" = amp ] && { [ $i -eq 0 ] && dir="$NEW/acp" || dir="$NEW/cli"; }
  if fetch "$url" "$dir" "$sum"; then ok "downloaded $(basename "${url%%\?*}")"; else fail "download or unpack ($url)"; fi
  i=$((i + 1))
done

step "Install"
# Copilot, Goose and OpenCode are single programs, kept at $DEST/<agent> as before (the app checks
# that path, and installs from earlier versions have it).
case "$AGENT" in
  copilot|goose|opencode)
    main=$(find "$NEW" -type f -name "$AGENT" | head -n1)
    [ -n "$main" ] || fail "the archive has no $AGENT program"
    [ "$main" = "$NEW/$AGENT" ] || mv -f "$main" "$NEW/$AGENT" || fail "move $AGENT"
    ;;
esac
# Every glibc program in the bundle runs on Termux's glibc. Node single-file builds (Copilot, and
# Cursor's unused *-sea files) are left untouched.
if [ "$AGENT" != copilot ]; then
  patched=0 unpatched=0
  while IFS= read -r -d '' f; do
    case "$f" in *-sea) continue ;; esac
    if [ "$(patchelf --print-interpreter "$f" 2>/dev/null)" = /lib/ld-linux-aarch64.so.1 ]; then
      if run_logged patchelf --set-interpreter "$GLIBC_LD" "$f"; then patched=$((patched + 1)); else unpatched=$((unpatched + 1)); bad "patch $(basename "$f")"; fi
    fi
  done < <(find "$NEW" -type f -perm -u+x -print0)
  # A half-patched bundle won't run: keep the working version instead of swapping it in.
  [ "$unpatched" -eq 0 ] || fail "$unpatched program(s) couldn't be patched; the installed version is unchanged"
  ok "$patched program(s) set to Termux's glibc"
fi
chmod -R u+rwX "$NEW" 2>/dev/null
# Swap in the new copy, so a failure above leaves the working version.
rm -rf "$DEST.old"
[ -e "$DEST" ] && mv "$DEST" "$DEST.old"
if mv "$NEW" "$DEST"; then ok "$DEST"; rm -rf "$DEST.old"; else [ -e "$DEST.old" ] && mv "$DEST.old" "$DEST"; fail "install"; fi
rm -rf "$WORK"

# glibc programs: no Bionic preload, and Termux's CA bundle for those that don't carry their own.
GLIBC="unset LD_PRELOAD; [ -f \"\$PREFIX/etc/tls/cert.pem\" ] && export SSL_CERT_FILE=\"\$PREFIX/etc/tls/cert.pem\""
# A wrapper that runs [target] (found in the bundle), optionally through the glibc loader.
wrap() { # NAME TARGET [loader]
  [ -n "$2" ] && [ -x "$2" ] || fail "$1: program not found in the download (${2:-nothing matched})"
  if [ "${3:-}" = loader ]; then
    write_wrapper "$1" "$GLIBC; exec /system/bin/env \"$GLIBC_LD\" \"$2\" \"\$@\""
  else
    write_wrapper "$1" "$GLIBC; exec \"$2\" \"\$@\""
  fi
}
case "$AGENT" in
  copilot) wrap copilot "$DEST/copilot" loader; CHECK=copilot ;;
  goose) wrap goose "$DEST/goose" loader; CHECK=goose ;;
  opencode) wrap opencode "$DEST/opencode"; CHECK=opencode ;;
  cline) wrap cline "$DEST/package/bin/cline"; CHECK=cline ;;
  kilo) wrap kilo "$(find "$DEST" -maxdepth 2 -type f -name kilo | head -n1)"; CHECK=kilo ;;
  # Cursor's launcher is a bash script that starts its own Node with index.js; do the same here.
  # Credentials go in a file: Android has no keyring.
  cursor)
    [ -x "$DEST/dist-package/node" ] && [ -f "$DEST/dist-package/index.js" ] || fail "cursor: node or index.js missing from the download"
    write_wrapper cursor-agent "$GLIBC
export CURSOR_INVOKED_AS=cursor-agent AGENT_CLI_CREDENTIAL_STORE=file NODE_COMPILE_CACHE=\"\${XDG_CACHE_HOME:-\$HOME/.cache}/cursor-compile-cache\"
exec \"$DEST/dist-package/node\" \"$DEST/dist-package/index.js\" \"\$@\""
    CHECK=cursor-agent ;;
  vibe) wrap vibe-acp "$(find "$DEST" -maxdepth 2 -type f -name vibe-acp | head -n1)"; CHECK=vibe-acp ;;
  kiro) wrap kiro-cli "$DEST/kirocli/bin/kiro-cli"; CHECK=kiro-cli ;;
  amp)
    wrap amp "$(find "$DEST/cli" -type f -name amp | head -n1)"
    wrap amp-acp "$(find "$DEST/acp" -type f -name amp-acp | head -n1)"
    CHECK="amp amp-acp" ;;
esac
# shellcheck disable=SC2086
finish_with_version $CHECK
