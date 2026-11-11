#!/usr/bin/env bash
# Installs or repairs the optional Local AI pack: Ollama and llama.cpp (models download separately).
# The package list and version checks come from the app (toolchain.sh), the same ones System checks uses.
# Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/local-ai-setup.log"
require_space "$YURLUNGR_SPACE_MB_LOCALAI"

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_PACKAGES_LOCALAI[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_LOCALAI[@]}"

setup_finish "Local AI ready. Run System checks again to see it listed."
