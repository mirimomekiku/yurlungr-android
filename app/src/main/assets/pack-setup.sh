#!/usr/bin/env bash
# Installs or repairs one optional pack (Go, Rust, Python…). The app installs this file once per pack,
# under that pack's script name (go-setup.sh, rust-setup.sh…), and the name picks the pack:
#
#   bash "$PREFIX/libexec/yurlungr/go-setup.sh"
#
# The log and status file follow the name too (go-setup.log, go-setup.status). The package list,
# space estimate, title and version checks come from the app (toolchain.sh), the same ones System
# checks uses. The headless browser has its own script (browser-setup.sh). Safe to re-run.
set -u
NAME=$(basename "$0" .sh)
case "$NAME" in *-setup) ;; *) printf 'run this as <pack>-setup.sh (the app installs one per pack)\n' >&2; exit 64 ;; esac
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/$NAME.log"

# From toolchain.sh: which pack this script name installs, e.g. go-setup → GO.
declare -A YURLUNGR_PACK_OF_SCRIPT  # an older toolchain.sh may not define it
PACK=${YURLUNGR_PACK_OF_SCRIPT[$NAME.sh]:-}
case "$PACK" in
  ''|*[!A-Z]*) bad "unknown pack for $NAME.sh; open the Yurlungr app once so it can update toolchain.sh"; setup_finish "" ;;
esac
eval 'space=${YURLUNGR_SPACE_MB_'"$PACK"':-0}; title=${YURLUNGR_TITLE_'"$PACK"':-$PACK}'
eval 'pkgs=("${YURLUNGR_PACKAGES_'"$PACK"'[@]}"); checks=("${YURLUNGR_CHECKS_'"$PACK"'[@]}")'
require_space "$space"

step "Packages"
apt_prepare && install_pkgs "${pkgs[@]}"

step "Versions"
hash -r
run_checks "${checks[@]}"

setup_finish "$title ready. Run System checks again to see it listed."
