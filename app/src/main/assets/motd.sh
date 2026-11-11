#!/usr/bin/env bash
# Dynamic welcome for interactive Yurlungr terminals; also available as yurlungr-status.
# Only public session summaries and credential-file presence are used. Never read credentials.
#
#    ╭─╮  ╭─╮  ╭─╮
#  ●─╯ ╰──╯ ╰──╯ ╰─╴  yurlungr
# ╭─ shell 1 · 1/1 ───────────────╮
# │ ~/projects/app        ⎇ main  │
# │ agents   0 working · 2 idle   │
# │ waiting  0 approvals          │
# │ ✓ Claude  ○ Codex  ○ Gemini   │
# ╰───────────────────────────────╯
set +x
# Widths below count characters, which needs a UTF-8 locale.
case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in
  *[Uu][Tt][Ff]-8*|*[Uu][Tt][Ff]8*) ;;
  *) export LC_ALL=C.UTF-8 ;;
esac
color=0
if [ -t 1 ] && [ "${TERM:-dumb}" != dumb ] && [ -z "${NO_COLOR+x}" ]; then color=1; fi
if [ "$color" = 1 ]; then
  dim=$'\033[2m'; ink=$'\033[1m'; reset=$'\033[0m'
  # The app's tile colours: lime, sky, lilac, peach; green for signed in.
  lime=$'\033[38;5;191m'; sky=$'\033[38;5;117m'; lilac=$'\033[38;5;183m'; peach=$'\033[38;5;216m'; green=$'\033[38;5;114m'
else
  dim=''; ink=''; reset=''; lime=''; sky=''; lilac=''; peach=''; green=''
fi

# ---- What to show -------------------------------------------------------------------------
# Each shell carries a stable ID; the app updates numbering after sessions are closed.
title='terminal'; working=''; idle=''; attention=''
summary="$PREFIX/var/run/yurlungr/status.tsv"
if [ -r "$summary" ]; then
  while IFS=$'\t' read -r kind a b c d e; do
    case "$kind" in
      session)
        if [ "$a" = "${YURLUNGR_SESSION_ID:-}" ]; then title="$d · $b/$c"; fi ;;
      agents) working="$a"; idle="$b"; attention="$c" ;;
    esac
  done < "$summary"
fi
location="${PWD:-$HOME}"
case "$location" in "$HOME") location='~' ;; "$HOME"/*) location="~/${location#"$HOME/"}" ;; esac
branch=''
if command -v git >/dev/null 2>&1; then
  if branch_name=$(git symbolic-ref --short HEAD 2>/dev/null); then
    branch="$branch_name"
  elif git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    branch=$(git rev-parse --short HEAD 2>/dev/null)
  fi
fi
# Strip control characters from names before writing them to a terminal.
branch=$(printf '%s' "$branch" | tr -d '\000-\037\177')
location=$(printf '%s' "$location" | tr -d '\000-\037\177')
title=$(printf '%s' "$title" | tr -d '\000-\037\177')

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

# ---- Layout -------------------------------------------------------------------------------
cols=${COLUMNS:-0}
case "$cols" in ''|*[!0-9]*) cols=0 ;; esac
[ "$cols" -gt 0 ] || cols=$(tput cols 2>/dev/null || echo 40)
case "$cols" in ''|*[!0-9]*) cols=40 ;; esac
width=$cols
[ "$width" -le 40 ] || width=40
[ "$width" -ge 28 ] || width=28
inner=$((width - 4)) # between "│ " and " │"
# Labels line up in a column when there's room.
if [ "$inner" -ge 30 ]; then gap_agents='   '; gap_waiting='  '; else gap_agents=' '; gap_waiting=' '; fi

repeat() { local out='' i; for ((i = 0; i < $2; i++)); do out+=$1; done; printf '%s' "$out"; }
# Keeps the end of a long path: "…/projects/app".
fit() { local text=$1 max=$2; if [ "${#text}" -gt "$max" ]; then text="…${text:${#text}-max+1}"; fi; printf '%s' "$text"; }
# One card line: plain texts measure the gap, painted ones are printed.
row() {
  local gap=$((inner - ${#1} - ${#2}))
  [ "$gap" -ge 1 ] || gap=1
  printf '%s│%s %s%*s%s %s│%s\n' "$dim" "$reset" "$3" "$gap" '' "$4" "$dim" "$reset"
}
# The wave mark in the app's colours, left to right.
paint() {
  local text=$1 out='' i band palette=("$lime" "$sky" "$lilac" "$peach")
  for ((i = 0; i < ${#text}; i++)); do
    band=$((i / 5)); [ "$band" -le 3 ] || band=3
    out+="${palette[band]}${text:i:1}"
  done
  printf '%s%s' "$out" "$reset"
}

printf '%s\n' "$(paint '   ╭─╮  ╭─╮  ╭─╮')"
printf '%s  %syurlungr%s\n' "$(paint ' ●─╯ ╰──╯ ╰──╯ ╰─╴')" "$ink" "$reset"

label=" $(fit "$title" $((width - 6))) "
printf '%s╭─%s%s%s%s╮%s\n' "$dim" "$reset$ink" "$label" "$reset$dim" "$(repeat ─ $((width - 3 - ${#label})))" "$reset"

# Where: the folder, and the branch when it's a repository.
right=''
[ -z "$branch" ] || right="⎇ $(fit "$branch" 14)"
left=$(fit "$location" $((inner - ${#right} - 2)))
row "$left" "$right" "$left" "${lilac}${right}${reset}"

if [ -n "$working" ]; then
  text="agents${gap_agents}$working working · $idle idle"
  row "$text" '' "${dim}agents${reset}${gap_agents}${working} working · ${idle} idle" ''
  approvals=approvals
  [ "${attention:-0}" != 1 ] || approvals=approval
  text="waiting${gap_waiting}${attention:-0} $approvals"
  waiting_color=''
  [ "${attention:-0}" = 0 ] || waiting_color=$peach
  row "$text" '' "${dim}waiting${reset}${gap_waiting}${waiting_color}${attention:-0} $approvals${reset}" ''
else
  row "agents   status unavailable" '' "${dim}agents   status unavailable${reset}" ''
fi

# Accounts of installed agents only, wrapped to the card.
plain=''; painted=''; legend_sign=0; legend_unknown=0
for entry in "Claude:$claude" "Codex:$codex" "Gemini:$gemini" "Antigravity:$agy"; do
  name=${entry%%:*}; mark=${entry#*:}
  [ "$mark" != '—' ] || continue
  case "$mark" in
    ✓) mark_color=$green ;;
    ○) mark_color=$peach; legend_sign=1 ;;
    *) mark_color=$dim; legend_unknown=1 ;;
  esac
  item="$mark $name"
  if [ -n "$plain" ] && [ $(( ${#plain} + 2 + ${#item} )) -gt "$inner" ]; then
    row "$plain" '' "$painted" ''
    plain=''; painted=''
  fi
  [ -z "$plain" ] || { plain+='  '; painted+='  '; }
  plain+=$item; painted+="${mark_color}${mark}${reset} ${name}"
done
if [ -n "$plain" ]; then row "$plain" '' "$painted" ''
else row "no agents installed yet" '' "${dim}no agents installed yet${reset}" ''; fi

printf '%s╰%s╯%s\n' "$dim" "$(repeat ─ $((width - 2)))" "$reset"

# A short key, only for the marks that are showing.
key=''
[ "$legend_sign" = 0 ] || key='○ sign in needed'
[ "$legend_unknown" = 0 ] || key+="${key:+ · }? unknown"
[ -z "$key" ] || printf '%s  %s%s\n' "$dim" "$key" "$reset"
printf '%s  yurlungr-status to refresh%s\n\n' "$dim" "$reset"
