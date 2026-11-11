#!/usr/bin/env bash
# Installs or repairs the optional Media & docs pack: ffmpeg, ImageMagick, Graphviz, and the TeX Live installer (run texlive-installer for LaTeX).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/media-setup.log"
require_space "$YURLUNGR_SPACE_MB_MEDIA"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_MEDIA[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_MEDIA[@]}"

setup_finish "Media & docs ready. Run System checks again to see it listed."
