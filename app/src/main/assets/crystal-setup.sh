#!/usr/bin/env bash
# Installs or repairs the optional Crystal pack: Crystal (with shards).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/crystal-setup.log"
require_space "$YURLUNGR_SPACE_MB_CRYSTAL"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_CRYSTAL[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_CRYSTAL[@]}"

setup_finish "Crystal ready. Run System checks again to see it listed."
