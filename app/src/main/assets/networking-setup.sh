#!/usr/bin/env bash
# Installs or repairs the optional Networking pack: dig, ip, nc, socat, nmap, xh, HTTPie and openssl.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/networking-setup.log"
require_space "$YURLUNGR_SPACE_MB_NETWORKING"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_NETWORKING[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_NETWORKING[@]}"

setup_finish "Networking ready. Run System checks again to see it listed."
