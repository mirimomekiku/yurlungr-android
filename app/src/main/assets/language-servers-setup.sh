#!/usr/bin/env bash
# Installs or repairs the optional Language servers pack: rust-analyzer, gopls, lua-language-server and ruff (clangd comes with Build tools).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/language-servers-setup.log"
require_space "$YURLUNGR_SPACE_MB_LANGUAGESERVERS"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_LANGUAGESERVERS[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_LANGUAGESERVERS[@]}"

setup_finish "Language servers ready. Run System checks again to see it listed."
