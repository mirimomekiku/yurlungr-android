#!/usr/bin/env bash
# Installs or repairs the optional Python data & science pack: Prebuilt numpy, pandas, scipy, Pillow, cryptography, lxml and matplotlib (no compiling; project venvs see them).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/python-science-setup.log"
require_space "$YURLUNGR_SPACE_MB_PYSCIENCE"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_PYSCIENCE[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_PYSCIENCE[@]}"

setup_finish "Python data & science ready. Run System checks again to see it listed."
