#!/usr/bin/env bash
# Installs or repairs the optional Data tools pack: yq (YAML), xmlstarlet (XML) and Miller (CSV).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/data-tools-setup.log"
require_space "$YURLUNGR_SPACE_MB_DATATOOLS"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_DATATOOLS[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_DATATOOLS[@]}"

setup_finish "Data tools ready. Run System checks again to see it listed."
