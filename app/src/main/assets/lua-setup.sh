#!/usr/bin/env bash
# Installs or repairs the optional Lua pack: Lua 5.4 with LuaRocks.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/lua-setup.log"
require_space "$YURLUNGR_SPACE_MB_LUA"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_LUA[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_LUA[@]}"

setup_finish "Lua ready. Run System checks again to see it listed."
