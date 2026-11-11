#!/usr/bin/env bash
# Installs or repairs the optional Dart pack: Dart SDK (Flutter itself isn't available on Android).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/dart-setup.log"
require_space "$YURLUNGR_SPACE_MB_DART"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_DART[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_DART[@]}"

setup_finish "Dart ready. Run System checks again to see it listed."
