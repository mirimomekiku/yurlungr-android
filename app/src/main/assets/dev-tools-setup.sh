#!/usr/bin/env bash
# Installs or repairs the developer tools: git, the GitHub CLI, Node.js, ripgrep, curl and
# termux-exec (which npm-based CLIs need for their #!/usr/bin/env shebangs). The package list
# comes from the app (toolchain.sh), the same list System checks verifies.
# Much smaller than the agents setup, which also installs these. Safe to re-run.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/dev-tools-setup.log"
require_space 400

step "Packages"
apt_prepare && install_pkgs "${YURLUNGR_DEV_PACKAGES[@]}"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_DEVTOOLS[@]}"

setup_finish "Developer tools ready. Terminals opened before this pick up termux-exec once restarted."
