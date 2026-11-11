#!/usr/bin/env bash
# Removes an optional pack's packages (the app passes the pack name, e.g. HASKELL):
#
#   bash pack-remove.sh <PACK>
#
# Never removes what the developer tools, agents or Android SDK setups install, or packages
# dpkg marks essential. apt also removes packages that depend on the removed ones; the list is
# printed first. Safe to re-run.
set -u
PACK=${1:-}
case "$PACK" in ''|*[!A-Z]*) printf 'usage: pack-remove.sh <PACK>\n' >&2; exit 64 ;; esac
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/pack-remove.log"

eval 'pkgs=("${YURLUNGR_PACKAGES_'"$PACK"'[@]}")' 2>/dev/null || pkgs=()
[ "${#pkgs[@]}" -gt 0 ] || { bad "unknown pack $PACK"; setup_finish ""; }
protected=" ${YURLUNGR_DEV_PACKAGES[*]} ${YURLUNGR_AGENT_PACKAGES[*]} ${YURLUNGR_AGENT_GLIBC_PACKAGES[*]} ${YURLUNGR_SDK_PACKAGES[*]} "

step "Packages"
remove=()
for p in "${pkgs[@]}"; do
  if ! dpkg -s "$p" >/dev/null 2>&1; then ok "$p (not installed)"; continue; fi
  case "$protected" in *" $p "*) ok "$p (kept: needed by the core setups)"; continue ;; esac
  if [ "$(dpkg-query -W -f='${Essential}' "$p" 2>/dev/null)" = yes ]; then ok "$p (kept: essential)"; continue; fi
  remove+=("$p")
done

if [ "${#remove[@]}" -gt 0 ]; then
  step "Removing ${remove[*]}"
  # What apt will take away, dependents included, before it does.
  apt-get -s remove "${remove[@]}" 2>/dev/null | sed -n 's/^Remv \([^ ]*\).*/  - \1/p'
  if run_logged with_timeout 1800 apt-get remove -y "${remove[@]}"; then ok "removed"; else bad "apt-get remove"; fi
  hash -r
fi

setup_finish "Removed. Downloaded package files can be cleared under Storage."
