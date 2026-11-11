#!/usr/bin/env bash
# Self-test of the development environment, as the app sets it up. Prints PASS/FAIL/SKIP per
# check and writes ~/self-test-report.txt.
#
#   bash self-test.sh          quick: reads only, no installs, no network
#   bash self-test.sh --full   also installs two small packs (Data tools, Archives), switches the
#                              JDK and back, and runs a Vite app (downloads about 30 MB)
set -u
FULL=0
[ "${1:-}" = --full ] && FULL=1
: "${PREFIX:?Run inside the Yurlungr runtime}"
REPORT="$HOME/self-test-report.txt"
TMP_REPORT="$REPORT.tmp"
: > "$TMP_REPORT" || exit 73
trap 'rm -f "$TMP_REPORT"' EXIT
pass=0; fail=0; skips=0

result() { # STATUS NAME DETAIL
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$TMP_REPORT"
  local color=33
  case "$1" in PASS) color=32; pass=$((pass + 1)) ;; FAIL) color=31; fail=$((fail + 1)) ;; *) skips=$((skips + 1)) ;; esac
  printf '\033[%sm%-4s\033[0m %-30s %s\n' "$color" "$1" "$2" "$3"
}
# NAME COMMAND: PASS when it exits 0, with its first line; FAIL with its last.
check() {
  local name=$1 out
  if out=$(timeout 120 bash -o pipefail -c "$2" 2>&1 </dev/null); then
    result PASS "$name" "$(printf '%s' "$out" | grep -m1 . | cut -c1-90)"
  else
    result FAIL "$name" "$(printf '%s' "$out" | grep . | tail -n 1 | cut -c1-90)"
  fi
}
section() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }

section "Environment"
check "toolchain manifest" "source \"\$PREFIX/libexec/yurlungr/toolchain.sh\" && echo \"\${#YURLUNGR_CHECKS_DEVTOOLS[@]} dev tools listed\""
if ls "$PREFIX"/lib/jvm/java-*-openjdk/bin/java >/dev/null 2>&1; then
  check "JAVA_HOME matches java" '[ -n "${JAVA_HOME:-}" ] && [ "$(readlink -f "$(command -v java)")" = "$(readlink -f "$JAVA_HOME/bin/java")" ] && echo "$JAVA_HOME"'
  check "Gradle sees every JDK" 'for j in "$PREFIX"/lib/jvm/java-*-openjdk; do grep -q "$j" "$HOME/.gradle/gradle.properties" || { echo "missing $j"; exit 1; }; done; echo ok'
else
  result SKIP "JAVA_HOME" "no JDK installed"
fi
check "user tools on PATH" 'case ":$PATH:" in *":$HOME/.local/bin:"*) echo ok ;; *) echo "~/.local/bin not on PATH"; exit 1 ;; esac'
check "PNPM_HOME" '[ -n "${PNPM_HOME:-}" ] && echo "$PNPM_HOME"'
check "shims" 'for s in yurlungr-open yurlungr-install yurlungr-notify; do command -v "$s" >/dev/null || { echo "missing $s"; exit 1; }; done; echo ok'

section "Installed tools"
# Every tool the app knows, checked only when its command exists (optional packs).
if source "$PREFIX/libexec/yurlungr/toolchain.sh" 2>/dev/null; then
  for var in $(compgen -A variable | grep '^YURLUNGR_CHECKS_'); do
    eval 'entries=("${'"$var"'[@]}")'
    for entry in "${entries[@]}"; do
      name=${entry%%$'\t'*}; cmd=${entry#*$'\t'}
      first=${cmd%% *}
      case "$first" in test|command|ls|python) ;; *) command -v "$first" >/dev/null 2>&1 || continue ;; esac
      check "$name" "$cmd"
    done
  done
fi

section "Workflows"
work=$(mktemp -d "$PREFIX/tmp/self-test.XXXXXX") || exit 73
trap 'rm -f "$TMP_REPORT"; rm -rf "$work"' EXIT
check "git commit and status" "cd '$work' && git init -q r && cd r && echo a > a && git add a && git -c user.name=t -c user.email=t@t commit -qm a && echo b >> a && git status --porcelain"
check "ripgrep search" "cd '$work' && printf 'x\\nneedle\\n' > f.txt && rg -n --no-heading needle ."
check "notification" "yurlungr-notify 'Yurlungr self-test' 'Notifications work' && echo sent"

if [ "$FULL" = 1 ]; then
  section "Packs (installs Data tools and Archives)"
  for pack in data-tools archives; do
    check "$pack pack" "bash \"\$PREFIX/libexec/yurlungr/$pack-setup.sh\" >/dev/null 2>&1 && echo installed"
  done

  section "JDK switch (and back)"
  jdks=("$PREFIX"/lib/jvm/java-*-openjdk)
  if [ "${#jdks[@]}" -ge 2 ] && [ -x "${jdks[0]}/bin/java" ]; then
    mode=$(sed -n 1p "$PREFIX/var/lib/dpkg/alternatives/java" 2>/dev/null)
    before=$(readlink -f "$PREFIX/etc/alternatives/java")
    other=""
    for j in "${jdks[@]}"; do [ "$(readlink -f "$j/bin/java")" != "$before" ] && other=$j && break; done
    check "switch to ${other##*/}" "update-alternatives --set java '$other/bin/java' >/dev/null && java -version 2>&1 | sed -n 1p && [ \"\$(readlink -f \"\$(command -v java)\")\" = \"\$(readlink -f '$other/bin/java')\" ]"
    if [ "$mode" = manual ]; then update-alternatives --set java "$before" >/dev/null; else update-alternatives --auto java >/dev/null; fi
    check "switched back" "[ \"\$(readlink -f \"\$PREFIX/etc/alternatives/java\")\" = '$before' ] && echo restored"
  else
    result SKIP "JDK switch" "needs two JDKs (Development environment → Install)"
  fi

  section "Vite app"
  if command -v npm >/dev/null; then
    port=5199
    check "npm install vite" "cd '$work' && mkdir -p vite && cd vite && printf '{\"private\":true,\"type\":\"module\"}' > package.json && echo '<h1>self-test</h1>' > index.html && npm install --no-audit --no-fund --silent vite@6 >/dev/null 2>&1 && ls node_modules/.bin/vite"
    if [ -x "$work/vite/node_modules/.bin/vite" ]; then
      (cd "$work/vite" && exec node_modules/.bin/vite --host 127.0.0.1 --port "$port" --strictPort >"$work/vite.log" 2>&1) &
      vite_pid=$!
      check "Vite serves the page" "for i in \$(seq 1 60); do curl -fsS http://127.0.0.1:$port/ 2>/dev/null | grep -q self-test && echo 'served in '\$i's' && exit 0; sleep 1; done; tail -n 3 '$work/vite.log'; exit 1"
      kill "$vite_pid" 2>/dev/null; wait "$vite_pid" 2>/dev/null
    fi
  else
    result SKIP "Vite app" "npm isn't installed"
  fi
fi

printf '\n%s passed, %s failed, %s skipped. Report: %s\n' "$pass" "$fail" "$skips" "$REPORT"
mv -f "$TMP_REPORT" "$REPORT"
trap 'rm -rf "$work"' EXIT
yurlungr-notify "Self-test: $pass passed, $fail failed" "$skips skipped. Details in ~/self-test-report.txt" >/dev/null 2>&1
[ "$fail" -eq 0 ]
