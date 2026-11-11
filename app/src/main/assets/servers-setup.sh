#!/usr/bin/env bash
# Installs or repairs the optional Servers & tunnels pack: nginx, Caddy, cloudflared (share a dev server through a public link) and Mosquitto (MQTT).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/servers-setup.log"
require_space "$YURLUNGR_SPACE_MB_SERVERS"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_SERVERS[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_SERVERS[@]}"

setup_finish "Servers & tunnels ready. Run System checks again to see it listed."
