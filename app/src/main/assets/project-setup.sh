#!/usr/bin/env bash
# Installs a project's dependencies for one detected stack (the app's StackDetector decides what):
#
#   bash project-setup.sh <project dir> "<termux packages>" "<step>" ["<step>"...]
#
# Missing Termux packages (the stack's toolchain) are installed first, then each step runs in the
# project folder, stopping at the first failure. Uses the shared setup helpers: one setup at a
# time, a log in ~/project-setup.log, no hidden prompts, bounded steps. Safe to re-run.
set -u
if [ $# -lt 3 ]; then
  printf 'usage: project-setup.sh <dir> "<packages>" <step>...\n' >&2
  exit 64
fi
DIR=$1
PKGS=$2
shift 2
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/project-setup.log"
cd "$DIR" || { bad "project folder $DIR"; setup_finish ""; }
require_space 300

if [ -n "$PKGS" ]; then
  step "Toolchain: $PKGS"
  missing=()
  for p in $PKGS; do
    dpkg -s "$p" >/dev/null 2>&1 && ok "$p (installed)" || missing+=("$p")
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    apt_prepare && install_pkgs "${missing[@]}"
    hash -r
  fi
  # Without its toolchain no step can work: stop with the reason instead of failing each one.
  [ "${#FAILED[@]}" -eq 0 ] || setup_finish ""
fi

for s in "$@"; do
  step "$s"
  # An hour each at most: first installs download a lot, but a stalled one must end.
  if run_logged with_timeout 3600 bash -o pipefail -c "$s"; then
    ok "done"
  else
    bad "$s"
    break
  fi
done

setup_finish "Dependencies installed in ${DIR##*/}."
