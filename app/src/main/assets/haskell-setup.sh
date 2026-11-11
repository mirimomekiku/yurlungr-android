#!/usr/bin/env bash
# Installs or repairs the optional Haskell pack: GHC and cabal (about 3 GB).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/haskell-setup.log"
require_space "$YURLUNGR_SPACE_MB_HASKELL"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_HASKELL[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_HASKELL[@]}"

setup_finish "Haskell ready. Run System checks again to see it listed."
