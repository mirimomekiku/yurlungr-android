#!/usr/bin/env bash
# Installs what was picked in System checks → Install tools, in one package run:
#
#   bash "$PREFIX/libexec/yurlungr/essentials-setup.sh" git node python uv GO RUST
#
# Lowercase names are essentials (Essentials.kt), uppercase ones optional packs that only install
# packages (Toolchain.kt's ToolSetup, e.g. GO). Their packages, space and version checks come from the
# app (toolchain.sh), the same ones System checks probes. Everything else picked (the agents, Ubuntu,
# Flutter, the browser, the Android SDK) has its own setup, which the app runs after this one: each
# holds the setup lock. Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/essentials-setup.log"

# Declared even if an older toolchain.sh didn't define them (then every name is unknown, and says so).
declare -gA YURLUNGR_ESSENTIAL_PACKAGES YURLUNGR_ESSENTIAL_TOOLS YURLUNGR_PACK_OF_SCRIPT
declare -ga YURLUNGR_CHECKS_ESSENTIALS

[ $# -gt 0 ] || { bad "nothing to install (pass names, e.g. git python GO)"; setup_finish ""; }

units=()              # the valid names, in order
declare -A unit_pkgs  # name -> its packages (space-separated)
declare -A unit_space # pack name -> MB it needs
for name in "$@"; do
  case "$name" in
    [a-z]*)
      case "$name" in *[!a-z0-9]*) bad "bad name: $name"; continue ;; esac
      if [ -z "${YURLUNGR_ESSENTIAL_PACKAGES[$name]+x}" ]; then
        bad "unknown item $name; open the Yurlungr app once so it can update toolchain.sh"
        continue
      fi
      unit_pkgs[$name]=${YURLUNGR_ESSENTIAL_PACKAGES[$name]}
      ;;
    [A-Z]*)
      case "$name" in *[!A-Z]*) bad "bad name: $name"; continue ;; esac
      # Only uppercase letters, so safe to build variable names from (as pack-setup.sh does).
      if ! declare -p "YURLUNGR_PACKAGES_$name" >/dev/null 2>&1; then
        bad "unknown pack $name; open the Yurlungr app once so it can update toolchain.sh"
        continue
      fi
      eval 'unit_pkgs[$name]="${YURLUNGR_PACKAGES_'"$name"'[*]}"; unit_space[$name]=${YURLUNGR_SPACE_MB_'"$name"':-0}'
      ;;
    *) bad "bad name: $name"; continue ;;
  esac
  units+=("$name")
done
# setup_finish exits only on failure.
[ "${#units[@]}" -gt 0 ] || { setup_finish "Nothing to install."; exit 0; }

# Essentials together stay well under 600 MB; packs say what they need.
space=0 essentials=0
for name in "${units[@]}"; do
  case "$name" in [a-z]*) essentials=1 ;; *) space=$(( space + ${unit_space[$name]:-0} )) ;; esac
done
require_space $(( space + essentials * 600 ))

step "Packages"
declare -A unit_failed seen
if apt_prepare; then
  for name in "${units[@]}"; do
    before=${#FAILED[@]}
    read -ra pkgs <<< "${unit_pkgs[$name]}"
    for p in "${pkgs[@]}"; do
      [ -n "${seen[$p]+x}" ] && continue # a package two picks share is installed once
      seen[$p]=1
      install_pkgs "$p"
    done
    [ "${#FAILED[@]}" -eq "$before" ] || unit_failed[$name]=1
  done
else
  for name in "${units[@]}"; do unit_failed[$name]=1; done
fi

step "Versions"
hash -r
for name in "${units[@]}"; do
  before=${#FAILED[@]}
  checks=()
  case "$name" in
    [a-z]*)
      read -ra tools <<< "${YURLUNGR_ESSENTIAL_TOOLS[$name]:-}"
      for entry in "${YURLUNGR_CHECKS_ESSENTIALS[@]}"; do
        for tool in "${tools[@]}"; do
          if [ "${entry%%$'\t'*}" = "$tool" ]; then checks+=("$entry"); break; fi
        done
      done
      ;;
    *) eval 'checks=("${YURLUNGR_CHECKS_'"$name"'[@]}")' ;;
  esac
  run_checks "${checks[@]}"
  [ "${#FAILED[@]}" -eq "$before" ] || unit_failed[$name]=1
done

# A pack installed and verified here is as good as a run of its own setup: record that, so System
# checks stops showing an older failed run of it.
for name in "${units[@]}"; do
  case "$name" in [A-Z]*) ;; *) continue ;; esac
  [ -z "${unit_failed[$name]+x}" ] || continue
  for script in "${!YURLUNGR_PACK_OF_SCRIPT[@]}"; do
    [ "${YURLUNGR_PACK_OF_SCRIPT[$script]}" = "$name" ] || continue
    status="$STATUS_DIR/${script%.sh}.status"
    printf 'exit\t0\npid\t%s\nlog\t%s\nfinished\t%s\n' "$$" "$LOG" "$(date +%s)" > "$status.tmp" 2>/dev/null \
      && mv -f "$status.tmp" "$status" 2>/dev/null
  done
done

setup_finish "Installed: $*. Run System checks again to see them listed."
