#!/usr/bin/env bash
# Installs or repairs the optional Security & signing pack: GnuPG (signed commits), age and git-crypt.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/security-setup.log"
require_space "$YURLUNGR_SPACE_MB_SECURITY"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_SECURITY[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_SECURITY[@]}"

setup_finish "Security & signing ready. Run System checks again to see it listed."
