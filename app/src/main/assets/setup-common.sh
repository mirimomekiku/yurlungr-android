#!/usr/bin/env bash
# Shared setup helpers. Source from Bash, then call setup_init with the log path.
set -o pipefail

setup_init() {
  if [ -z "${PREFIX:-}" ] || [ -z "${HOME:-}" ] || [ ! -x "$PREFIX/bin/bash" ]; then
    printf 'Setup needs the installed Yurlungr Linux environment. Open System checks first.\n' >&2
    exit 64
  fi
  local tool
  for tool in apt-get tee timeout flock; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      printf 'Setup cannot start: missing %s. Repair the Linux environment in System checks.\n' "$tool" >&2
      exit 69
    fi
  done
  LOG="$1"
  mkdir -p "$PREFIX/var/lock" "$PREFIX/tmp" "${LOG%/*}" || exit 73
  # One lock for both installers. The kernel releases it even after a crash or reboot.
  exec 9>"$PREFIX/var/lock/yurlungr-setup.lock" || exit 73
  if ! flock -n 9; then
    printf 'Another setup is already running. Wait for it to finish, or end its terminal process, then retry.\n' >&2
    exit 75
  fi
  if [ -f "$LOG" ] && ! mv -f "$LOG" "$LOG.previous"; then
    printf 'Cannot preserve the previous setup log: %s\n' "$LOG" >&2
    exit 73
  fi
  if ! : > "$LOG"; then
    printf 'Cannot write setup log: %s. Check free storage and retry.\n' "$LOG" >&2
    exit 73
  fi
  trap 'exit 130' INT
  trap 'exit 143' TERM
  trap 'status=$?; if [ "$status" -ne 0 ]; then printf "\nSetup stopped (exit %s). Details: %s. Fix the error above and rerun setup.\n" "$status" "$LOG" >&2; fi' EXIT
  FAILED=()
  COLS=$(stty size 2>/dev/null | cut -d' ' -f2) || COLS=80
  case "$COLS" in ''|*[!0-9]*) COLS=80 ;; esac
  if [ "$COLS" -lt 20 ] || [ "$COLS" -gt 300 ]; then COLS=80; fi
  export DEBIAN_FRONTEND=noninteractive
  APT_OPTS=(-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confnew)
}

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok() { printf '\033[32m  ✓ %s\033[0m\n' "$*"; }
bad() { printf '\033[31m  ✗ %s\033[0m\n' "$*"; FAILED+=("$*"); }

run_logged() {
  "$@" 9>&- 2>&1 | tee -a "$LOG" | while IFS= read -r line || [ -n "$line" ]; do
    line=${line##*$'\r'}
    if [ -n "$line" ]; then printf '\r\033[K\033[2m  %s\033[0m' "${line:0:$((COLS - 3))}"; fi
  done
  local -a statuses=("${PIPESTATUS[@]}")
  printf '\r\033[K'
  if [ "${statuses[1]}" -ne 0 ]; then
    printf 'Cannot save setup output to %s. Check free storage.\n' "$LOG" >&2
    return "${statuses[1]}"
  fi
  if [ "${statuses[0]}" -ne 0 ]; then return "${statuses[0]}"; fi
  return "${statuses[2]}"
}

check() {
  local output status
  if output=$(timeout 60 bash -c "$2" 9>&- 2>&1); then status=0; else status=$?; fi
  if ! printf '\n[%s exit=%s]\n%s\n' "$1" "$status" "$output" >> "$LOG"; then
    bad "$1 (cannot write $LOG)"
    return 1
  fi
  if [ "$status" -eq 0 ] && [ -n "$output" ]; then
    ok "$1: ${output%%$'\n'*}"
  else
    bad "$1 (exit $status; see $LOG)"
  fi
}

setup_finish() {
  if [ "${#FAILED[@]}" -ne 0 ]; then
    printf '\n\033[1;31mFailed: %s\033[0m (details: %s)\n' "${FAILED[*]}" "$LOG" >&2
    exit 1
  fi
  printf '\n\033[1;32m%s\033[0m\n' "$1"
}
