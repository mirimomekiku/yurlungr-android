#!/usr/bin/env bash
# No downloads or package installs. All writes stay inside the supplied cache fixture.
set -eu
COMMON="$1"
ROOT="$2"
RUNTIME_BASH=$(command -v bash)
mkdir -p "$ROOT/prefix/bin" "$ROOT/home"
ln -s "$RUNTIME_BASH" "$ROOT/prefix/bin/bash"
export PREFIX="$ROOT/prefix" HOME="$ROOT/home"
# setup_init requires the app-generated manifest; a minimal one is enough here.
mkdir -p "$ROOT/prefix/libexec/yurlungr"
printf 'YURLUNGR_DEV_PACKAGES=(git)\nYURLUNGR_CHECKS_DEVTOOLS=()\n' > "$ROOT/prefix/libexec/yurlungr/toolchain.sh"
source "$COMMON"
setup_init "$HOME/setup.log"
LOG_PATH="$LOG"

# No TTY, invalid widths, and unterminated output must still work and be logged.
[ "$COLS" -ge 20 ] && [ "$COLS" -le 300 ]
run_logged bash -c 'printf without-newline'
grep -q 'without-newline' "$LOG"
if run_logged bash -c 'echo expected-command-error; exit 7'; then exit 10; else [ "$?" -eq 7 ]; fi
grep -q expected-command-error "$LOG"

# Printing a version string before failure must not produce a successful check.
check version-error 'echo fake-version; exit 9'
[ "${#FAILED[@]}" -eq 1 ]
if (setup_finish 'must not appear') >"$ROOT/summary" 2>&1; then exit 11; fi
grep -q 'Failed: version-error' "$ROOT/summary"
if grep -q 'must not appear' "$ROOT/summary"; then exit 12; fi

# A competing setup must fail before rotating/truncating the first setup's log.
before=$(cat "$LOG")
if bash -c 'source "$1"; setup_init "$2"' -- "$COMMON" "$LOG" 9>&- >"$ROOT/locked" 2>&1; then
  exit 13
else
  [ "$?" -eq 75 ]
fi
grep -q 'Another setup is already running' "$ROOT/locked"
[ "$(cat "$LOG")" = "$before" ]

# A logging failure cannot masquerade as a successful command.
LOG="$ROOT/nonexistent/output.log"
if run_logged bash -c 'echo output'; then exit 14; fi
LOG="$LOG_PATH"

# A retry keeps the previous log, and the lock has no stale PID-file recovery path.
flock -u 9
exec 9>&-

# A failed run leaves a status file (exit code and failed steps) for System checks.
if bash -c 'source "$1"; setup_init "$2"; bad "demo step"; setup_finish unused' -- "$COMMON" "$HOME/demo-setup.log" >/dev/null 2>&1; then exit 15; fi
grep -q "$(printf '^exit\t1$')" "$PREFIX/var/lib/yurlungr/setup/demo-setup.status"
grep -q "$(printf '^failed\tdemo step$')" "$PREFIX/var/lib/yurlungr/setup/demo-setup.status"

# While it runs the status says so (-1), and a hang-up (closing the terminal tab) records 129,
# not the last command's 0: a half-finished setup must never read as completed.
HUP_STATUS="$PREFIX/var/lib/yurlungr/setup/hup-setup.status"
# Processes started from the app's own threads can inherit SIGHUP blocked or ignored (terminal
# sessions can't: termux.c unblocks every signal), and bash can't trap a signal ignored at entry.
hup_masked() {
  local field mask
  for field in SigBlk SigIgn; do
    mask=$(awk -v k="$field:" '$1 == k {print $2}' "/proc/$$/status")
    [ $(( 0x${mask:-0} & 1 )) -ne 0 ] && return 0
  done
  return 1
}
if hup_masked; then
  printf 'SKIP: hang-up case (SIGHUP is blocked or ignored by this launcher: %s)\n' "$(grep -E '^Sig(Blk|Ign)' /proc/$$/status | tr '\n' ' ')"
else
# Like every real step (run_logged/check), the stand-in work closes the lock fd (9>&-), so it can't
# keep holding the setup lock once its setup is gone.
bash -c 'source "$1"; setup_init "$2"; sleep 30 9>&- & echo $! > "$3"; wait $!; setup_finish unused' \
  -- "$COMMON" "$HOME/hup-setup.log" "$ROOT/hup-work.pid" </dev/null >/dev/null 2>&1 &
hup_pid=$!
for _ in $(seq 50); do grep -q "$(printf '^exit\t-1$')" "$HUP_STATUS" 2>/dev/null && break; sleep 0.1; done
grep -q "$(printf '^exit\t-1$')" "$HUP_STATUS"
grep -q "$(printf '^pid\t')" "$HUP_STATUS"
kill -HUP "$hup_pid"
if wait "$hup_pid"; then exit 16; fi
grep -q "$(printf '^exit\t129$')" "$HUP_STATUS"
kill "$(cat "$ROOT/hup-work.pid")" 2>/dev/null || true
fi

setup_init "$LOG"
[ "$(cat "$LOG.previous")" = "$before" ]
[ ! -s "$LOG" ]
setup_finish 'PASS: setup errors, exit codes, logging, retry and exclusive lock'
