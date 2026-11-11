#!/usr/bin/env bash
# Installs or repairs the optional Task runners & watchers pack: just, direnv, watchexec, entr and inotify-tools.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/task-runners-setup.log"
require_space "$YURLUNGR_SPACE_MB_TASKRUNNERS"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_TASKRUNNERS[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_TASKRUNNERS[@]}"

setup_finish "Task runners & watchers ready. Run System checks again to see it listed."
