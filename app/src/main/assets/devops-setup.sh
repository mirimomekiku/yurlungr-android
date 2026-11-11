#!/usr/bin/env bash
# Installs or repairs the optional DevOps pack: kubectl, Helm, and adb/fastboot (for example adb to this phone over wireless debugging).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/devops-setup.log"
require_space "$YURLUNGR_SPACE_MB_DEVOPS"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_DEVOPS[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_DEVOPS[@]}"

setup_finish "DevOps ready. Run System checks again to see it listed."
