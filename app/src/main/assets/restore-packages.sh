#!/usr/bin/env bash
# Reinstalls the packages that were on the phone before the base system was reinstalled:
#
#   bash restore-packages.sh <list file>
#
# The app saved the list before replacing the old system (RestoreList.kt): `pkg <name>` lines for
# packages installed by hand, `npm <name>` for global npm packages. Whatever is already installed is
# skipped, and packages the repository no longer has are listed and left out rather than failing
# the whole install. Setups and agents in the list are rerun by the app after this. Safe to re-run.
set -u
LIST=${1:-}
if [ -z "$LIST" ] || [ ! -r "$LIST" ]; then printf 'usage: restore-packages.sh <list file>\n' >&2; exit 64; fi
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/restore-packages.log"

# Installed now ("ii" in dpkg's database), as " a b c " for quick lookups.
installed=" $(dpkg-query -W -f='${db:Status-Abbrev} ${Package}\n' 2>/dev/null | awk '$1 == "ii" { print $2 }' | tr '\n' ' ') "
pkgs=() npms=()
while read -r kind name _; do
  case "$kind" in
    pkg)
      [[ "$name" =~ ^[a-z0-9][a-z0-9.+-]*$ ]] || continue
      case "$installed" in *" $name "*) ;; *) pkgs+=("$name") ;; esac
      ;;
    npm)
      [[ "$name" =~ ^(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*$ ]] || continue
      [ -d "$PREFIX/lib/node_modules/$name" ] || npms+=("$name")
      ;;
  esac
done < "$LIST"

# The names apt can install now, out of those given.
available() {
  apt-cache --no-all-versions show "$@" 2>/dev/null | sed -n 's/^Package: //p' | sort -u | tr '\n' ' '
}

step "Packages (${#pkgs[@]} to reinstall)"
if [ "${#pkgs[@]}" -gt 0 ]; then
  apt_prepare || setup_finish ""
  # Repositories first (glibc-repo, tur-repo, x11-repo…): what they provide is only found after them.
  repos=() rest=()
  for p in "${pkgs[@]}"; do case "$p" in *-repo) repos+=("$p") ;; *) rest+=("$p") ;; esac; done
  if [ "${#repos[@]}" -gt 0 ]; then
    install_pkgs "${repos[@]}"
    run_logged with_timeout 600 apt-get "${APT_LOCK[@]}" update || bad "package index update after adding repositories"
  fi
  if [ "${#rest[@]}" -gt 0 ]; then
    have=" $(available "${rest[@]}") "
    get=()
    for p in "${rest[@]}"; do
      case "$have" in
        *" $p "*) get+=("$p") ;;
        *) warn "$p: no longer in the package repository, skipped" ;;
      esac
    done
    if [ "${#get[@]}" -gt 0 ]; then
      # All at once (one dependency solve); if that fails, one by one, so one bad package doesn't stop the rest.
      if run_logged with_timeout 3600 apt-get install "${APT_OPTS[@]}" "${get[@]}"; then
        ok "${#get[@]} packages"
      else
        printf '  Installing them together failed; trying one at a time.\n'
        install_pkgs "${get[@]}"
      fi
    fi
  fi
  hash -r
else
  ok "nothing to reinstall"
fi

step "Global npm packages (${#npms[@]} to reinstall)"
if [ "${#npms[@]}" -gt 0 ]; then
  if command -v npm >/dev/null 2>&1; then
    for p in "${npms[@]}"; do
      run_logged with_timeout 1800 npm install -g "$p" && ok "$p" || bad "npm install -g $p"
    done
  else
    bad "npm isn't installed (Node.js wasn't in the list): install the developer tools, then run this again"
  fi
else
  ok "nothing to reinstall"
fi

setup_finish "Packages restored. Setups and agents follow, if there are any."
