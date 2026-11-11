#!/usr/bin/env bash
# Installs or repairs the optional Java & Kotlin pack: JDK 17, Kotlin, Maven and Gradle.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/jvm-setup.log"
require_space "$YURLUNGR_SPACE_MB_JVM"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_JVM[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_JVM[@]}"

setup_finish "Java & Kotlin ready. Run System checks again to see it listed."
