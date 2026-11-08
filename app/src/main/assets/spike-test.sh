#!/usr/bin/env bash
# Yurlungr Phase 0 spike: which agent CLIs and Android build tools run natively in the
# embedded runtime? Results go to ~/spike-report.txt (shown in the app under Settings → System runtime → Agent CLI test → Report).
#
#   bash ~/spike-test.sh                 install everything, then test
#   bash ~/spike-test.sh --skip-install  only re-run the version checks
#
# Installs are logged to ~/spike-test.log. Safe to re-run.
set -u

REPORT="$HOME/spike-report.txt"
LOG="$HOME/spike-test.log"
SKIP_INSTALL=0
[ "${1:-}" = "--skip-install" ] && SKIP_INSTALL=1

export DEBIAN_FRONTEND=noninteractive
APT_OPTS=(-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confnew)

: > "$REPORT"

record() { # STATUS NAME DETAIL
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$REPORT"
  case "$1" in
    PASS) color=32 ;; FAIL) color=31 ;; *) color=36 ;;
  esac
  printf '\033[%sm%-4s\033[0m %-24s %s\n' "$color" "$1" "$2" "$3"
}

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

COLS=$(stty size 2>/dev/null | cut -d' ' -f2)
COLS=${COLS:-80}

# Runs a command, appending its output to $LOG while showing the latest line in place,
# so long installs (OpenJDK, npm) visibly make progress. Returns the command's status.
run_logged() {
  "$@" 2>&1 | tee -a "$LOG" | while IFS= read -r line; do
    line=${line##*$'\r'}
    [ -n "$line" ] && printf '\r\033[K\033[2m  %s\033[0m' "${line:0:$((COLS - 3))}"
  done
  local status=${PIPESTATUS[0]}
  printf '\r\033[K'
  return "$status"
}

install_pkg() {
  printf '\033[2m  installing %s…\033[0m' "$1"
  if run_logged apt-get install "${APT_OPTS[@]}" "$1"; then
    record PASS "pkg:$1" "installed"
  else
    record FAIL "pkg:$1" "apt-get install failed, see $LOG"
    return 1
  fi
}

install_npm() {
  printf '\033[2m  installing %s…\033[0m' "$1"
  if run_logged npm install -g --loglevel=http "$1"; then
    record PASS "npm:$1" "installed"
  else
    record FAIL "npm:$1" "npm install failed, see $LOG"
  fi
}

# check NAME 'shell command' -> PASS with first output line, or FAIL with last line.
check() {
  local out status
  out=$(timeout 120 bash -c "$2" 2>&1)
  status=$?
  if [ $status -eq 0 ]; then
    record PASS "$1" "$(printf '%s\n' "$out" | grep -m1 . )"
  else
    record FAIL "$1" "exit $status: $(printf '%s\n' "$out" | grep . | tail -n1)"
  fi
}

record INFO device "$(uname -m) · Android $(getprop ro.build.version.release) · $(getprop ro.product.manufacturer) $(getprop ro.product.model)"
echo "Log: $LOG"

if [ $SKIP_INSTALL -eq 0 ]; then
  step "Updating packages (this can take a few minutes)"
  if run_logged apt-get update && run_logged apt-get "${APT_OPTS[@]}" upgrade; then
    record PASS apt-upgrade "ok"
  else
    record FAIL apt-upgrade "see $LOG"
  fi

  step "Base tools"
  for p in nodejs-lts git tmux openssh python clang make binutils; do install_pkg "$p"; done

  step "Android build tools"
  for p in openjdk-17 gradle apksigner d8; do install_pkg "$p"; done
  install_pkg aapt2 || install_pkg aapt

  step "glibc-runner (runs glibc-only Linux binaries without proot)"
  if install_pkg glibc-repo; then
    run_logged apt-get update
    install_pkg glibc-runner
  fi

  step "Agent CLIs (npm)"
  for p in @google/gemini-cli @openai/codex @anthropic-ai/claude-code; do install_npm "$p"; done
fi

step "Version checks"
check node      'node --version'
check npm       'npm --version'
check git       'git --version'
check tmux      'tmux -V'
check java      'java -version'
check gradle    'gradle --version | grep -m1 Gradle'
check aapt2     'aapt2 version'
check apksigner 'apksigner --version'
check d8        'd8 --version'
check grun      'command -v grun'
check gemini    'gemini --version'
check codex     'codex --version'
check claude    'claude --version'

step "Summary"
pass=$(grep -c '^PASS' "$REPORT")
fail=$(grep -c '^FAIL' "$REPORT")
echo "$pass passed, $fail failed. Report: $REPORT"
echo "Open the app: Settings → System runtime, tap ⟳ to re-run the checks, then Report under Tools."
