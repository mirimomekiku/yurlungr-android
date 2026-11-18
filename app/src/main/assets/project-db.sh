#!/usr/bin/env bash
# Starts and stops a project's own database servers (PostgreSQL, Redis, MariaDB). The app runs it:
#
#   bash project-db.sh start <postgres|redis|mariadb> <data folder> <socket folder> <port> <database>
#   bash project-db.sh stop  <postgres|redis|mariadb> <data folder>
#
# The password comes in YURLUNGR_DB_PASSWORD (never on a command line, where `ps` shows it).
# Data lives in the app's home, outside the project, so it's never committed. Servers listen on
# 127.0.0.1 only and need the password over TCP. The first start initializes the data folder;
# an interrupted first start is redone. A pid file is trusted only while its pid is still the
# server it names: a pid reused by another process is never signalled. Safe to re-run.
set -u
ACTION=${1:-}; KIND=${2:-}; DIR=${3:-}
fail() { printf '%s\n' "$*" >&2; exit 1; }
usage() { fail "usage: project-db.sh start <kind> <dir> <socket dir> <port> <database> | stop <kind> <dir>"; }
case "$KIND" in postgres|redis|mariadb) ;; *) usage ;; esac
case "$DIR" in /*) ;; *) usage ;; esac
case "$DIR" in *[!A-Za-z0-9/._-]*) fail "unexpected characters in $DIR" ;; esac

# The name the pid file's process must have (first word of its command line).
case "$KIND" in
  postgres) PIDFILE="$DIR/postgres/postmaster.pid"; NAMES="postgres" ;;
  redis) PIDFILE="$DIR/redis/redis.pid"; NAMES="redis-server" ;;
  mariadb) PIDFILE="$DIR/mariadb/mariadb.pid"; NAMES="mariadbd mysqld" ;;
esac
LOG="$DIR/$KIND.log"

# Prints the server's pid when the pid file names a live process with the right name.
server_pid() {
  local pid first n
  [ -r "$PIDFILE" ] || return 1
  pid=$(sed -n 1p "$PIDFILE" 2>/dev/null | tr -d '[:space:]')
  case "$pid" in ''|*[!0-9]*) return 1 ;; esac
  [ "$pid" -gt 1 ] && [ -r "/proc/$pid/cmdline" ] || return 1
  first=$(tr '\0' '\n' < "/proc/$pid/cmdline" 2>/dev/null | sed -n 1p)
  first=${first%% *}; first=${first##*/}
  for n in $NAMES; do [ "$first" = "$n" ] && { printf '%s\n' "$pid"; return 0; }; done
  return 1
}

# A pid file whose process is gone (or is now something else) would stop the server from starting.
clear_stale() {
  if [ -e "$PIDFILE" ] && ! server_pid >/dev/null; then rm -f "$PIDFILE"; fi
}

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# Waits until the pid in the pid file is gone (it was checked to be the server just before).
wait_gone() { # pid seconds
  local i=0
  while [ "$i" -lt $(( $2 * 5 )) ]; do
    [ -d "/proc/$1" ] || return 0
    server_pid >/dev/null || return 0
    sleep 0.2; i=$((i + 1))
  done
  return 1
}

stop_server() {
  local pid
  if ! pid=$(server_pid); then clear_stale; echo "not running"; return 0; fi
  if [ "$KIND" = postgres ]; then
    pg_ctl -D "$DIR/postgres" stop -m fast -w -t 30 >>"$LOG" 2>&1 || fail "pg_ctl stop failed (see $LOG)"
  else
    # Redis and MariaDB both shut down cleanly (saving their data) on SIGTERM.
    kill -TERM "$pid" 2>/dev/null
    wait_gone "$pid" 30 || fail "$KIND didn't stop within 30 s (pid $pid)"
  fi
  echo "stopped"
}

if [ "$ACTION" = stop ]; then
  [ $# -eq 3 ] || usage
  stop_server
  exit 0
fi

[ "$ACTION" = start ] && [ $# -eq 6 ] || usage
SOCK=$4; PORT=$5; DB=$6
PW=${YURLUNGR_DB_PASSWORD:-}
case "$SOCK" in /*) ;; *) usage ;; esac
case "$SOCK" in *[!A-Za-z0-9/._-]*) fail "unexpected characters in $SOCK" ;; esac
case "$PORT" in ''|*[!0-9]*) usage ;; esac
[ "$PORT" -ge 1024 ] && [ "$PORT" -le 65535 ] || fail "port out of range: $PORT"
case "$DB" in [a-z]*) ;; *) fail "bad database name" ;; esac
case "$DB" in *[!a-z0-9_]*) fail "bad database name" ;; esac
# Letters and digits only: it goes into SQL and config files unescaped.
case "$PW" in ''|*[!A-Za-z0-9]*) fail "missing or bad password" ;; esac
[ "${#PW}" -ge 16 ] || fail "password too short"

umask 077
mkdir -p "$DIR" "$SOCK" || fail "can't create $DIR"
clear_stale
if pid=$(server_pid); then echo "already running (pid $pid)"; exit 0; fi
if port_open "$PORT"; then fail "port $PORT is already in use by another program"; fi
printf '\n== %s start on port %s, %s ==\n' "$KIND" "$PORT" "$(date)" >>"$LOG"

need() { command -v "$1" >/dev/null 2>&1 || fail "$1 isn't installed. Install Databases in System checks."; }

start_postgres() {
  local d="$DIR/postgres" pwfile rc
  need initdb; need pg_ctl; need psql
  if [ ! -s "$d/PG_VERSION" ]; then
    # Nothing usable there: an interrupted first start. initdb needs an empty folder.
    rm -rf "$d"
    pwfile="$DIR/.initdb-pw.$$"
    printf '%s\n' "$PW" > "$pwfile" || fail "can't write $pwfile"
    # Local socket connections (only this app's user can reach the socket) are trusted; TCP needs the password.
    initdb -D "$d" -U postgres --pwfile="$pwfile" --auth-local=trust --auth-host=scram-sha-256 -E UTF8 --no-locale >>"$LOG" 2>&1
    rc=$?
    rm -f "$pwfile"
    [ "$rc" -eq 0 ] || { rm -rf "$d"; fail "initdb failed (see $LOG)"; }
  fi
  # Every option is ours (digits and app paths): pg_ctl hands -o to a shell.
  pg_ctl -D "$d" -l "$LOG" -w -t 90 -o "-c listen_addresses=127.0.0.1 -p $PORT -k $SOCK" start >/dev/null 2>>"$LOG" \
    || fail "PostgreSQL didn't start (see $LOG)"
  if ! psql -h "$SOCK" -p "$PORT" -U postgres -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$DB'" 2>>"$LOG" | grep -q 1; then
    psql -h "$SOCK" -p "$PORT" -U postgres -d postgres -c "CREATE DATABASE \"$DB\"" >>"$LOG" 2>&1 || fail "couldn't create database $DB (see $LOG)"
  fi
}

start_redis() {
  local d="$DIR/redis"
  need redis-server
  mkdir -p "$d"
  # Written each start (the port may have changed). requirepass: other apps on the phone can reach 127.0.0.1 too.
  cat > "$d/redis.conf" <<EOF || fail "can't write $d/redis.conf"
bind 127.0.0.1
port $PORT
protected-mode yes
daemonize yes
pidfile $PIDFILE
logfile $LOG
dir $d
requirepass $PW
EOF
  redis-server "$d/redis.conf" >>"$LOG" 2>&1 || fail "Redis didn't start (see $LOG)"
  local i=0
  until port_open "$PORT"; do
    i=$((i + 1)); [ "$i" -lt 100 ] || fail "Redis didn't open port $PORT (see $LOG)"
    sleep 0.2
  done
}

start_mariadb() {
  local d="$DIR/mariadb" sock="$SOCK/mariadb.sock" daemon installdb client admin fresh=0 pid i
  daemon=$(command -v mariadbd || command -v mysqld) || fail "MariaDB isn't installed. Install Databases in System checks."
  installdb=$(command -v mariadb-install-db || command -v mysql_install_db) || fail "mariadb-install-db is missing"
  client=$(command -v mariadb || command -v mysql) || fail "the mariadb client is missing"
  admin=$(command -v mariadb-admin || command -v mysqladmin) || fail "mariadb-admin is missing"
  if [ ! -f "$d/.yurlungr-initialized" ]; then
    rm -rf "$d"; mkdir -p "$d"
    "$installdb" --no-defaults --basedir="$PREFIX" --datadir="$d" --auth-root-authentication-method=normal --skip-test-db >>"$LOG" 2>&1 \
      || { rm -rf "$d"; fail "mariadb-install-db failed (see $LOG)"; }
    fresh=1
  fi
  launch() { # extra options
    # skip-name-resolve: accounts match by IP only, so the host-named root install-db made never applies.
    nohup "$daemon" --no-defaults --datadir="$d" --socket="$sock" --pid-file="$PIDFILE" --log-error="$LOG" --skip-name-resolve "$@" >>"$LOG" 2>&1 </dev/null &
    i=0
    until "$admin" --no-defaults --socket="$sock" ping >/dev/null 2>&1; do
      i=$((i + 1)); [ "$i" -lt 300 ] || fail "MariaDB didn't start (see $LOG)"
      sleep 0.2
    done
  }
  if [ "$fresh" = 1 ]; then
    # root has no password yet: set it with networking off, then restart listening on 127.0.0.1.
    launch --skip-networking
    "$client" --no-defaults --socket="$sock" -u root >>"$LOG" 2>&1 <<SQL || { stop_server >/dev/null; fail "couldn't set up MariaDB users (see $LOG)"; }
ALTER USER 'root'@'localhost' IDENTIFIED BY '$PW';
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '$PW';
ALTER USER 'root'@'127.0.0.1' IDENTIFIED BY '$PW';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
DROP USER IF EXISTS ''@'localhost';
DROP USER IF EXISTS 'root'@'::1';
FLUSH PRIVILEGES;
SQL
    stop_server >/dev/null || fail "couldn't restart MariaDB after setup"
    : > "$d/.yurlungr-initialized"
  fi
  launch --bind-address=127.0.0.1 --port="$PORT"
  MYSQL_PWD="$PW" "$client" --no-defaults --socket="$sock" -u root -e "CREATE DATABASE IF NOT EXISTS \`$DB\`" >>"$LOG" 2>&1 \
    || fail "couldn't create database $DB (see $LOG)"
}

case "$KIND" in
  postgres) start_postgres ;;
  redis) start_redis ;;
  mariadb) start_mariadb ;;
esac
pid=$(server_pid) || fail "$KIND started but its pid file is missing (see $LOG)"
echo "running (pid $pid, port $PORT)"
