#!/usr/bin/env bash
# Compatibility report: which tools, agent CLIs and Android build tools actually run in the
# embedded runtime? Results go to ~/spike-report.txt (shown in the app under
# Settings → System runtime → Agent CLI compatibility → Report).
#
#   bash ~/spike-test.sh
#
# Check-only: it installs nothing (use the setups in the app for that), so it can't undo or
# race them. The checks come from the app's toolchain.sh, the same list System checks uses.
set -u

REPORT="$HOME/spike-report.txt"
MANIFEST="${PREFIX:?Install the Linux environment first}/libexec/yurlungr/toolchain.sh"
if [ "${1:-}" = "--skip-install" ]; then shift; fi # accepted for old instructions; nothing to skip
if [ ! -r "$MANIFEST" ] || ! source "$MANIFEST"; then
  printf 'Missing %s. Open the Yurlungr app once so it can write it, then retry.\n' "$MANIFEST" >&2
  exit 69
fi

: > "$REPORT" || { printf 'Cannot write %s. Check free storage.\n' "$REPORT" >&2; exit 73; }

record() { # STATUS NAME DETAIL
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$REPORT"
  case "$1" in
    PASS) color=32 ;; FAIL) color=31 ;; *) color=36 ;;
  esac
  printf '\033[%sm%-4s\033[0m %-24s %s\n' "$color" "$1" "$2" "$3"
}

# check NAME 'shell command' -> PASS with its first output line, or FAIL with exit code and last line.
check() {
  local out status
  out=$(timeout 120 bash -o pipefail -c "$2" 2>&1 </dev/null)
  status=$?
  if [ $status -eq 0 ] && [ -n "$out" ]; then
    record PASS "$1" "$(printf '%s\n' "$out" | grep -m1 .)"
  else
    case $status in
      124) why="timed out" ;;
      127) why="not installed" ;;
      126) why="can't run" ;;
      *) why="exit $status" ;;
    esac
    record FAIL "$1" "$why: $(printf '%s\n' "$out" | grep . | tail -n1)"
  fi
}

record INFO device "$(uname -m) · Android $(getprop ro.build.version.release) · $(getprop ro.product.manufacturer) $(getprop ro.product.model)"

printf '\n\033[1;36m==> Version checks\033[0m\n'
for entry in "${YURLUNGR_CHECKS_DEVTOOLS[@]}" "${YURLUNGR_CHECKS_AGENTS[@]}" "${YURLUNGR_CHECKS_ANDROIDSDK[@]}"; do
  check "${entry%%$'\t'*}" "${entry#*$'\t'}"
done
check grun 'command -v grun'

pass=$(grep -c '^PASS' "$REPORT")
fail=$(grep -c '^FAIL' "$REPORT")
printf '\n%s passed, %s failed. Report: %s\n' "$pass" "$fail" "$REPORT"
echo "Open the app: Settings → System runtime, tap ⟳ to re-run the checks, then Report under Tools."
