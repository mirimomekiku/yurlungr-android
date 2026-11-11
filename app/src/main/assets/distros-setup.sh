#!/usr/bin/env bash
# Installs or repairs the optional Linux distros pack: proot-distro: Ubuntu, Debian or Arch in proot, for glibc-only tools (slower; distros are large).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/distros-setup.log"
require_space "$YURLUNGR_SPACE_MB_DISTROS"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_DISTROS[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_DISTROS[@]}"

setup_finish "Linux distros ready. Run System checks again to see it listed."
