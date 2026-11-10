#!/usr/bin/env bash
# Installs or repairs the optional Build tools pack: clang, make, cmake, pkg-config, binutils.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/build-tools-setup.log"
require_space "$YURLUNGR_SPACE_MB_BUILDTOOLS"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_BUILDTOOLS[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_BUILDTOOLS[@]}"

setup_finish "Build tools ready. Run System checks again to see it listed."
