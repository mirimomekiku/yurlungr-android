#!/usr/bin/env bash
# Installs or repairs the optional Databases pack: SQLite, PostgreSQL, MariaDB and Redis (servers start on demand).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/databases-setup.log"
require_space "$YURLUNGR_SPACE_MB_DATABASES"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_DATABASES[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_DATABASES[@]}"

setup_finish "Databases ready. Run System checks again to see it listed."
