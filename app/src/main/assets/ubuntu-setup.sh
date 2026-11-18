#!/usr/bin/env bash
# Installs or repairs the optional Ubuntu environment: proot-distro, then `proot-distro install ubuntu`,
# for tools that need glibc. Run commands in it with `yurlungr-ubuntu 'command'` (the app installs
# that helper): it runs them in the current folder, with this home bound at the same path inside.
# The package list, space estimate and version checks come from the app (toolchain.sh).
# Safe to re-run: a working Ubuntu is kept; one left half-installed is removed and installed again.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/ubuntu-setup.log"
require_space "$YURLUNGR_SPACE_MB_UBUNTU"
ROOTFS="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"

step "Packages"
apt_prepare || setup_finish ""
install_pkgs "${YURLUNGR_PACKAGES_UBUNTU[@]}"
hash -r
command -v proot-distro >/dev/null 2>&1 || setup_finish ""

step "Ubuntu"
works() { with_timeout 300 proot-distro login ubuntu -- true 9>&- </dev/null >/dev/null 2>&1; }
if [ -d "$ROOTFS" ] && [ -x "$ROOTFS/usr/bin/bash" ] && works; then
  ok "already installed"
else
  if [ -d "$ROOTFS" ]; then
    # An interrupted install: proot-distro won't install over it.
    run_logged with_timeout 900 proot-distro remove ubuntu && ok "removed an unfinished install" || { bad "proot-distro remove ubuntu"; setup_finish ""; }
  fi
  # Downloads Ubuntu's root file system (about 50 MB, several hundred MB unpacked).
  if run_logged with_timeout 3600 proot-distro install ubuntu && works; then ok "Ubuntu installed"; else bad "proot-distro install ubuntu"; fi
fi

step "Versions"
run_checks "${YURLUNGR_CHECKS_UBUNTU[@]}"

setup_finish "Ubuntu ready. Run a command in it with: yurlungr-ubuntu 'uname -a'"
