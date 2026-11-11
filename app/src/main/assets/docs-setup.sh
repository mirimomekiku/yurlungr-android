#!/usr/bin/env bash
# Installs or repairs the optional Docs & static sites pack: pandoc, Hugo, Zola, mdBook and man pages (mandoc).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/docs-setup.log"
require_space "$YURLUNGR_SPACE_MB_DOCS"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_DOCS[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_DOCS[@]}"

setup_finish "Docs & static sites ready. Run System checks again to see it listed."
