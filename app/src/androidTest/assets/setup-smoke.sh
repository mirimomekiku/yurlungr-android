#!/usr/bin/env bash
# No downloads or package installs. All writes stay inside the supplied cache fixture.
set -eu
COMMON="$1"
ROOT="$2"
RUNTIME_BASH=$(command -v bash)
mkdir -p "$ROOT/prefix/bin" "$ROOT/home"
ln -s "$RUNTIME_BASH" "$ROOT/prefix/bin/bash"
export PREFIX="$ROOT/prefix" HOME="$ROOT/home"
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
setup_init "$LOG"
[ "$(cat "$LOG.previous")" = "$before" ]
[ ! -s "$LOG" ]
setup_finish 'PASS: setup errors, exit codes, logging, retry and exclusive lock'
