#!/usr/bin/env bash
# Installs or repairs the optional Python pack: Python 3 and pip.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/python-setup.log"
require_space "$YURLUNGR_SPACE_MB_PYTHON"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_PYTHON[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_PYTHON[@]}"

setup_finish "Python ready. Run System checks again to see it listed."
