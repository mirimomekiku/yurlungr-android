#!/usr/bin/env bash
# Installs or repairs the optional Archives pack: 7-Zip, xz, zstd and brotli.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/archives-setup.log"
require_space "$YURLUNGR_SPACE_MB_ARCHIVES"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_ARCHIVES[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_ARCHIVES[@]}"

setup_finish "Archives ready. Run System checks again to see it listed."
