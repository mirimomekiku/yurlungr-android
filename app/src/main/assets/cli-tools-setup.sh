#!/usr/bin/env bash
# Installs or repairs the optional CLI tools pack: fd, bat, eza, hyperfine, lazygit, gitui, git-lfs, shellcheck, shfmt, tree-sitter.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/cli-tools-setup.log"
require_space "$YURLUNGR_SPACE_MB_CLITOOLS"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_CLITOOLS[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_CLITOOLS[@]}"

setup_finish "CLI tools ready. Run System checks again to see it listed."
