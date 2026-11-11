#!/usr/bin/env bash
# Installs or repairs the optional Debugging pack: gdb, lldb, strace, ltrace and valgrind.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/debugging-setup.log"
require_space "$YURLUNGR_SPACE_MB_DEBUGGING"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_DEBUGGING[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_DEBUGGING[@]}"

setup_finish "Debugging ready. Run System checks again to see it listed."
