#!/usr/bin/env bash
# Installs or repairs the optional Flutter pack (experimental): the Flutter SDK, stable channel, inside
# the Ubuntu environment at /opt/flutter, set up for the web target only. Needs the Ubuntu
# environment pack first. Build and run with yurlungr-ubuntu, e.g.
#
#   yurlungr-ubuntu 'flutter run -d web-server --web-port 8080'
#
# Big downloads: the SDK (git), its Dart SDK and the web tools, about 2-3 GB on disk in all.
# The package list, space estimate and version checks come from the app (toolchain.sh). Safe to re-run:
# an SDK already in place is kept, and an interrupted download starts over.
set -u
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/flutter-setup.log"
require_space "$YURLUNGR_SPACE_MB_FLUTTER"
ROOTFS="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"

step "Ubuntu environment"
if [ ! -x "$ROOTFS/usr/bin/bash" ]; then
  bad "Ubuntu isn't installed. Install the Ubuntu environment pack in System checks first, then run this again."
  setup_finish ""
fi
apt_prepare || setup_finish ""
install_pkgs "${YURLUNGR_PACKAGES_FLUTTER[@]}"
hash -r
command -v proot-distro >/dev/null 2>&1 || setup_finish ""

# Runs a bash script inside Ubuntu (as its root user), logged like every other step.
in_ubuntu() { # seconds script
  run_logged with_timeout "$1" proot-distro login ubuntu --shared-tmp -- env DEBIAN_FRONTEND=noninteractive bash -c "$2"
}

step "Ubuntu packages"
in_ubuntu 3600 'apt-get update && apt-get install -y --no-install-recommends ca-certificates git curl unzip xz-utils zip' \
  && ok "git, curl, unzip, xz-utils, zip" || { bad "Ubuntu packages (apt-get in Ubuntu)"; setup_finish ""; }

step "Flutter SDK (stable)"
# Cloned next to its final place and moved in once complete, so an interrupted clone is never mistaken for an SDK.
in_ubuntu 3600 '
set -e
if [ -x /opt/flutter/bin/flutter ] && git -C /opt/flutter rev-parse --git-dir >/dev/null 2>&1; then
  echo "Flutter SDK already in /opt/flutter"
else
  rm -rf /opt/flutter.partial
  git clone --depth 1 --branch stable https://github.com/flutter/flutter.git /opt/flutter.partial
  rm -rf /opt/flutter
  mv /opt/flutter.partial /opt/flutter
fi
git config --global --get-all safe.directory 2>/dev/null | grep -qx /opt/flutter || git config --global --add safe.directory /opt/flutter
printf "%s\n" "export PATH=\"/opt/flutter/bin:\$PATH\"" > /etc/profile.d/flutter.sh
' && ok "/opt/flutter, on PATH for login shells" || { bad "Flutter SDK download"; setup_finish ""; }

step "Flutter for the web"
# The first run downloads the Dart SDK; precache adds the web tools. Other targets stay off.
in_ubuntu 3600 'export PATH="/opt/flutter/bin:$PATH" CI=true
flutter config --no-analytics >/dev/null 2>&1 || true
flutter config --enable-web && flutter precache --web' \
  && ok "web target ready" || bad "flutter precache --web"
in_ubuntu 600 'export PATH="/opt/flutter/bin:$PATH" CI=true; flutter --version' && ok "flutter --version" || bad "flutter --version"

step "Versions"
run_checks "${YURLUNGR_CHECKS_FLUTTER[@]}"

setup_finish "Flutter ready (experimental, web only). In a project: yurlungr-ubuntu 'flutter run -d web-server --web-port 8080'"
