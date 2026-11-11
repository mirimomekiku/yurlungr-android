#!/usr/bin/env bash
# Installs or repairs the optional Scala pack: Scala 3 (runs on the JDK).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/scala-setup.log"
require_space "$YURLUNGR_SPACE_MB_SCALA"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_SCALA[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_SCALA[@]}"

setup_finish "Scala ready. Run System checks again to see it listed."
