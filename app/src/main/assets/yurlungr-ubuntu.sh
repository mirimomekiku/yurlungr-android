#!/usr/bin/env bash
# Runs one bash command inside the Ubuntu environment (proot-distro), for tools that need glibc:
#
#   yurlungr-ubuntu 'flutter build web'
#
# The command runs with `bash -lc` (a login shell: /etc/profile.d puts Flutter and others on PATH)
# in the same folder as here: this home is bound at the same absolute path inside Ubuntu, so
# project paths match. PORT, HOST and a few other variables are passed through; name more in
# YURLUNGR_UBUNTU_ENV (space-separated). Exits 69 when Ubuntu isn't installed. Installed by the
# Yurlungr app (rewritten on every launch).
set -u
if [ $# -ne 1 ]; then
  printf "usage: yurlungr-ubuntu '<command>'  (runs it with bash -lc inside Ubuntu, in this folder)\n" >&2
  exit 64
fi
ROOTFS="${PREFIX:-/data/data/com.termux/files/usr}/var/lib/proot-distro/installed-rootfs/ubuntu"
if ! command -v proot-distro >/dev/null 2>&1 || [ ! -x "$ROOTFS/usr/bin/bash" ]; then
  printf 'The Ubuntu environment is not installed. Install it in System checks → Ubuntu environment, then try again.\n' >&2
  exit 69
fi

cwd=$(pwd -P 2>/dev/null) || cwd=$HOME
case "$cwd" in
  "$HOME"|"$HOME"/*) ;;
  *) printf 'yurlungr-ubuntu: %s is outside %s, which is all Ubuntu sees of this phone; running in %s\n' "$cwd" "$HOME" "$HOME" >&2; cwd=$HOME ;;
esac

# Exported inside rather than given to proot-distro, so values reach the command exactly (printf %q quotes them).
script=""
for name in PORT HOST NODE_ENV CI TERM COLORTERM LANG NO_COLOR FORCE_COLOR ${YURLUNGR_UBUNTU_ENV:-}; do
  case "$name" in ''|[0-9]*|*[!A-Za-z0-9_]*) continue ;; esac
  # Termux's own paths and preloads would break Ubuntu's programs.
  case "$name" in PATH|HOME|PREFIX|LD_PRELOAD|LD_LIBRARY_PATH|TMPDIR|SHELL|PWD) continue ;; esac
  if [ -n "${!name+x}" ]; then script+="export $name=$(printf '%q' "${!name}"); "; fi
done
script+="cd -- $(printf '%q' "$cwd") || exit 1"$'\n'"$1"

exec proot-distro login ubuntu --shared-tmp --bind "$HOME:$HOME" -- bash -lc "$script"
