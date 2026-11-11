#!/usr/bin/env bash
# Installs or repairs the optional Native libraries pack: What native npm and pip packages link against: libvips (sharp), cairo and pango (canvas), image, XML and SQLite libraries.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/native-libs-setup.log"
require_space "$YURLUNGR_SPACE_MB_NATIVELIBS"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_NATIVELIBS[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_NATIVELIBS[@]}"

setup_finish "Native libraries ready. Run System checks again to see it listed."
