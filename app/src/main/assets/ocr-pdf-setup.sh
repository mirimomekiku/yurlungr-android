#!/usr/bin/env bash
# Installs or repairs the optional OCR & PDF pack: Tesseract OCR, poppler (pdftotext…) and qpdf.
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/ocr-pdf-setup.log"
require_space "$YURLUNGR_SPACE_MB_OCR"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_OCR[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_OCR[@]}"

setup_finish "OCR & PDF ready. Run System checks again to see it listed."
