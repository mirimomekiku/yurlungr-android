#!/usr/bin/env bash
# Installs or repairs the optional Nim pack: Nim with nimble.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/nim-setup.log"
require_space "$YURLUNGR_SPACE_MB_NIM"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_NIM[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_NIM[@]}"

setup_finish "Nim ready. Run System checks again to see it listed."
