#!/usr/bin/env bash
# Installs or repairs the optional Go pack: the Go toolchain.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/go-setup.log"
require_space "$YURLUNGR_SPACE_MB_GO"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_GO[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_GO[@]}"

setup_finish "Go ready. Run System checks again to see it listed."
