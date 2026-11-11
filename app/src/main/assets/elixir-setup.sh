#!/usr/bin/env bash
# Installs or repairs the optional Elixir pack: Erlang/OTP and Elixir with mix.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/elixir-setup.log"
require_space "$YURLUNGR_SPACE_MB_ELIXIR"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_ELIXIR[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_ELIXIR[@]}"

setup_finish "Elixir ready. Run System checks again to see it listed."
