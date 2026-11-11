#!/usr/bin/env bash
# Installs or repairs the optional Shell & terminal pack: tmux, htop, zsh, fish and starship.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/shell-setup.log"
require_space "$YURLUNGR_SPACE_MB_SHELL"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_SHELL[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_SHELL[@]}"

setup_finish "Shell & terminal ready. Run System checks again to see it listed."
