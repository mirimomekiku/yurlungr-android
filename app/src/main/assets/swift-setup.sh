#!/usr/bin/env bash
# Installs or repairs the optional Swift pack: Swift toolchain.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/swift-setup.log"
require_space "$YURLUNGR_SPACE_MB_SWIFT"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_SWIFT[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_SWIFT[@]}"

setup_finish "Swift ready. Run System checks again to see it listed."
