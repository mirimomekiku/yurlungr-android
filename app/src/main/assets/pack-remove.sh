#!/usr/bin/env bash
# Removes an optional pack's packages (the app passes the pack name, e.g. HASKELL):
#
#   bash pack-remove.sh <PACK>
#
# Never removes what the developer tools, agents or Android SDK setups install, packages another
# installed pack also lists, or packages dpkg marks essential. apt would also remove packages that
# depend on the removed ones: it simulates first and stops if that would take any of those.
# Safe to re-run.
set -u
PACK=${1:-}
case "$PACK" in ''|*[!A-Z]*) printf 'usage: pack-remove.sh <PACK>\n' >&2; exit 64 ;; esac
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/pack-remove.log"

eval 'pkgs=("${YURLUNGR_PACKAGES_'"$PACK"'[@]}")' 2>/dev/null || pkgs=()
[ "${#pkgs[@]}" -gt 0 ] || { bad "unknown pack $PACK"; setup_finish ""; }
protected=" ${YURLUNGR_DEV_PACKAGES[*]} ${YURLUNGR_AGENT_PACKAGES[*]} ${YURLUNGR_AGENT_GLIBC_PACKAGES[*]} ${YURLUNGR_SDK_PACKAGES[*]} "

# Ubuntu and Flutter live in Ubuntu's root file system rather than in packages.
DISTROS_DIR="$PREFIX/var/lib/proot-distro/installed-rootfs"
case "$PACK" in
  FLUTTER)
    step "Flutter SDK"
    rm -rf "$DISTROS_DIR/ubuntu/opt/flutter" "$DISTROS_DIR/ubuntu/opt/flutter.partial" "$DISTROS_DIR/ubuntu/etc/profile.d/flutter.sh" \
      && ok "removed /opt/flutter from Ubuntu" || bad "removing /opt/flutter"
    # proot-distro stays: Ubuntu needs it.
    setup_finish "Flutter removed. The Ubuntu environment stays installed."
    exit 0
    ;;
  UBUNTU)
    step "Ubuntu"
    if [ -d "$DISTROS_DIR/ubuntu" ]; then
      run_logged with_timeout 1800 proot-distro remove ubuntu && ok "removed Ubuntu (and Flutter in it)" || { bad "proot-distro remove ubuntu"; setup_finish ""; }
    else
      ok "Ubuntu (not installed)"
    fi
    ;;
esac
# proot-distro runs the distros still installed: keep it while there are any.
if [ -n "$(ls -A "$DISTROS_DIR" 2>/dev/null)" ]; then protected+="proot-distro "; fi
mine=" ${pkgs[*]} "
# Packages other installed packs list (a pack counts as installed when a package only it lists
# is there): those stay, e.g. python when Python Science goes but the Python pack stays.
shared=" "
for var in $(compgen -A variable | grep '^YURLUNGR_PACKAGES_'); do
  [ "$var" = "YURLUNGR_PACKAGES_$PACK" ] && continue
  eval 'other=("${'"$var"'[@]}")'
  installed=0
  for q in "${other[@]}"; do
    case "$mine" in *" $q "*) continue ;; esac
    dpkg -s "$q" >/dev/null 2>&1 && { installed=1; break; }
  done
  [ "$installed" = 1 ] && shared+="${other[*]} "
done

step "Packages"
remove=()
for p in "${pkgs[@]}"; do
  if ! dpkg -s "$p" >/dev/null 2>&1; then ok "$p (not installed)"; continue; fi
  case "$protected" in *" $p "*) ok "$p (kept: needed by the core setups)"; continue ;; esac
  case "$shared" in *" $p "*) ok "$p (kept: another installed pack uses it)"; continue ;; esac
  if [ "$(dpkg-query -W -f='${Essential}' "$p" 2>/dev/null)" = yes ]; then ok "$p (kept: essential)"; continue; fi
  remove+=("$p")
done

if [ "${#remove[@]}" -gt 0 ]; then
  step "Removing ${remove[*]}"
  # What apt would take away, dependents included; stop if that reaches anything kept above.
  if ! sim=$(apt-get -s remove "${remove[@]}" 2>&1); then
    printf '%s\n' "$sim" >> "$LOG"; bad "apt couldn't plan the removal"; setup_finish ""
  fi
  gone=$(printf '%s\n' "$sim" | sed -n 's/^Remv \([^ :]*\).*/\1/p')
  printf '%s\n' "$gone" | sed 's/^/  - /'
  blocked=""
  for g in $gone; do
    case "$protected$shared" in *" $g "*) blocked+=" $g" ;; esac
    [ "$(dpkg-query -W -f='${Essential}' "$g" 2>/dev/null)" = yes ] && blocked+=" $g"
  done
  if [ -n "$blocked" ]; then
    bad "removing this pack would also remove${blocked} (used by the core setups or another pack); nothing was removed"
    setup_finish ""
  fi
  if run_logged with_timeout 1800 apt-get remove -y "${APT_LOCK[@]}" "${remove[@]}"; then ok "removed"; else bad "apt-get remove"; fi
  hash -r
fi

setup_finish "Removed. Downloaded package files can be cleared under Storage."
