#!/usr/bin/env bash
# Installs or repairs the optional JavaScript runtimes pack: pnpm, Yarn, Bun and Deno (Node.js is in the developer tools).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/js-runtimes-setup.log"
require_space "$YURLUNGR_SPACE_MB_JSRUNTIMES"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_JSRUNTIMES[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_JSRUNTIMES[@]}"

setup_finish "JavaScript runtimes ready. Run System checks again to see it listed."
