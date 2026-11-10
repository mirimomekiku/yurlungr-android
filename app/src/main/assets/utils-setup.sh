#!/usr/bin/env bash
# Installs or repairs the optional Utilities pack: jq, OpenSSH, zip, wget, rsync and friends.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/utils-setup.log"
require_space "$YURLUNGR_SPACE_MB_UTILS"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_UTILS[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_UTILS[@]}"

setup_finish "Utilities ready. Run System checks again to see it listed."
