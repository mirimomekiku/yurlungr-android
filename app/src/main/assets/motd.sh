#!/usr/bin/env bash
# Dynamic welcome for interactive Yurlungr terminals; also available as yurlungr-status.
# Only public session summaries and credential-file presence are used. Never read credentials.
set +x
color=0
if [ -t 1 ] && [ "${TERM:-dumb}" != dumb ] && [ -z "${NO_COLOR+x}" ]; then color=1; fi
if [ "$color" = 1 ]; then
  dim=$'\033[2m'; ink=$'\033[1m'; reset=$'\033[0m'
else
  dim=''; ink=''; reset=''
fi
# Each shell carries a stable ID; the app updates numbering after sessions are closed.
terminal=''; agents=''; attention=''
summary="$PREFIX/var/run/yurlungr/status.tsv"
if [ -r "$summary" ]; then
  while IFS=$'\t' read -r kind a b c d e; do
    case "$kind" in
      session)
        if [ "$a" = "${YURLUNGR_SESSION_ID:-}" ]; then terminal="$b / $c · $d"; fi ;;
      agents) agents="$a working / $b idle"; attention="$c" ;;
    esac
  done < "$summary"
fi
printf '  %sTERMINAL%s   %s\n' "$ink" "$reset" "${terminal:-current shell}"
location="${PWD:-$HOME}"
case "$location" in "$HOME") location='~' ;; "$HOME"/*) location="~/${location#"$HOME/"}" ;; esac
printf '  %sLOCATION%s   %s\n' "$ink" "$reset" "$location"
branch='—'
if command -v git >/dev/null 2>&1; then
  if branch_name=$(git symbolic-ref --short HEAD 2>/dev/null); then
    branch="$branch_name"
  elif git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    branch=$(git rev-parse --short HEAD 2>/dev/null)
  fi
fi
# Strip control characters from names before writing them to a terminal.
branch=$(printf '%s' "$branch" | tr -d '\000-\037\177')
printf '  %sBRANCH%s     %s\n' "$ink" "$reset" "$branch"
printf '  %sAGENTS%s     %s\n' "$ink" "$reset" "${agents:-summary unavailable}"
approval_word=approvals
[ "${attention:-0}" != 1 ] || approval_word=approval
printf '  %sATTENTION%s  %s\n\n' "$ink" "$reset" "${attention:-0} $approval_word pending"

account() {
  local installed="$1" credential="$2"
  if [ ! -e "$PREFIX/$installed" ]; then printf '—'
  elif [ "$credential" = unknown ]; then printf '?'
  elif [ -f "$HOME/$credential" ]; then printf '✓'
  else printf '○'; fi
}
claude=$(account bin/claude-agent-acp .claude/.credentials.json)
# Claude's adapter also requires its patched native binary.
[ -e "$PREFIX/libexec/yurlungr/claude-glibc" ] || claude='—'
codex=$(account bin/codex-acp .codex/auth.json)
[ -e "$PREFIX/bin/codex" ] || codex='—'
gemini=$(account bin/gemini .gemini/oauth_creds.json)
agy=$(account bin/agy unknown)
printf '  %sACCOUNTS%s\n' "$ink" "$reset"
printf '  Claude %s    Codex %s\n' "$claude" "$codex"
printf '  Gemini %s    Antigravity %s\n' "$gemini" "$agy"
printf '\n%s  ✓ signed in  ○ sign in\n  ? unknown    — not installed\n  yurlungr-status to refresh%s\n\n' "$dim" "$reset"
