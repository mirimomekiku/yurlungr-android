#!/usr/bin/env bash
# Installs or repairs the optional Headless browser pack: Chromium, from Termux's x11 repository
# (enabled here), for Puppeteer (executablePath) and Selenium. Playwright doesn't support Android.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/browser-setup.log"
require_space "$YURLUNGR_SPACE_MB_BROWSER"

step "x11 repository"
apt_prepare && install_pkgs x11-repo
# The new repository's package list.
run_logged with_timeout 600 apt-get update && ok "package lists" || bad "package lists"

step "Packages"
install_pkgs "${YURLUNGR_PACKAGES_BROWSER[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_BROWSER[@]}"

setup_finish "Headless browser ready. Puppeteer: puppeteer.launch({ executablePath: '$PREFIX/bin/chromium-browser' })."
