#!/usr/bin/env bash
# Boot a Fabric server that already has mods/ + fabric-server-launch.jar,
# run `genHexBook export`, and copy <server>/hexbook/site to --out.
#
# The dedicated-server watchdog is disabled (max-tick-time=-1) because
# /genHexBook builds hexdoc on the server thread. Stdin is held open so an
# EOF does not stop the server. The command itself is sent over RCON.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RCON_PY="$HERE/rcon.py"

SERVER=""
HEXDOC=""
SITE_URL="https://mielib-hexbook.pages.dev"
OUT=""
TIMEOUT=900
START_TIMEOUT=600

usage() {
  echo "usage: export_site.sh --server-dir DIR --hexdoc PATH [--site-url URL] [--out DIR] [--timeout SEC] [--start-timeout SEC]" >&2
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --server-dir) SERVER="${2:-}"; shift 2 ;;
    --hexdoc) HEXDOC="${2:-}"; shift 2 ;;
    --site-url) SITE_URL="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --timeout) TIMEOUT="${2:-}"; shift 2 ;;
    --start-timeout) START_TIMEOUT="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done

[ -n "$SERVER" ] && [ -n "$HEXDOC" ] || usage
[ -x "$HEXDOC" ] || { echo "hexdoc is not executable: $HEXDOC" >&2; exit 1; }
[ -f "$SERVER/fabric-server-launch.jar" ] || { echo "missing $SERVER/fabric-server-launch.jar" >&2; exit 1; }
compgen -G "$SERVER/mods/*.jar" >/dev/null || { echo "no jars in $SERVER/mods" >&2; exit 1; }

SITE_URL="${SITE_URL%/}"
SERVER="$(cd "$SERVER" && pwd)"
mkdir -p "$SERVER/logs" "$SERVER/mods"

# git commit inside the dumped project needs an identity. The dump is local
# and is not pushed. Real GIT_* values from the environment win.
export GIT_AUTHOR_NAME="${GIT_AUTHOR_NAME:-genhexbook}"
export GIT_AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-genhexbook@localhost}"
export GIT_COMMITTER_NAME="${GIT_COMMITTER_NAME:-$GIT_AUTHOR_NAME}"
export GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL:-$GIT_AUTHOR_EMAIL}"

RCON_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_hex(12))')"
export RCON_PASSWORD

umask 077
cat >"$SERVER/eula.txt" <<'EOF'
eula=true
EOF
cat >"$SERVER/server.properties" <<EOF
online-mode=false
server-ip=127.0.0.1
server-port=25565
enable-rcon=true
rcon.port=25575
rcon.password=${RCON_PASSWORD}
broadcast-rcon-to-ops=true
spawn-protection=0
level-name=genhexbook
motd=GenHexBook
view-distance=2
simulation-distance=2
max-tick-time=-1
sync-chunk-writes=false
enable-query=false
EOF
chmod 600 "$SERVER/server.properties"
umask 022

fifo="$SERVER/console.fifo"
rm -f "$fifo"
mkfifo "$fifo"

server_pid=""
holder_pid=""

dump_logs() {
  echo "----- console.out (tail) -----"
  tail -n 140 "$SERVER/logs/console.out" 2>/dev/null || true
  echo "----- latest.log (tail) -----"
  tail -n 140 "$SERVER/logs/latest.log" 2>/dev/null || true
  if [ -f "$SERVER/hexbook/hexdoc-build.log" ]; then
    echo "----- hexdoc-build.log (tail) -----"
    tail -n 80 "$SERVER/hexbook/hexdoc-build.log" || true
  fi
}

log_has() {
  local pat="$1" f
  for f in "$SERVER/logs/latest.log" "$SERVER/logs/console.out"; do
    if [ -f "$f" ] && grep -q -F -- "$pat" "$f"; then
      return 0
    fi
  done
  return 1
}

cleanup() {
  local status=$?
  if [ -n "$server_pid" ] && kill -0 "$server_pid" 2>/dev/null; then
    python3 "$RCON_PY" --command stop --timeout 30 >/dev/null 2>&1 || true
    sleep 2
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  if [ -n "$holder_pid" ]; then
    kill "$holder_pid" 2>/dev/null || true
  fi
  rm -f "$fifo"
  exit "$status"
}
trap cleanup EXIT

# hexdoc renders item icons with ModernGL. A headless runner has no DISPLAY;
# xvfb-run gives the server (and the hexdoc child it spawns) a software X server.
java_prefix=()
if [ -z "${DISPLAY:-}" ]; then
  if ! command -v xvfb-run >/dev/null 2>&1; then
    echo "hexdoc needs a display to render icons. Install xvfb, or set DISPLAY." >&2
    exit 1
  fi
  export LIBGL_ALWAYS_SOFTWARE=1
  java_prefix=(xvfb-run -a)
fi

# Hold stdin open. Closing it (or pointing it at /dev/null) stops the server.
python3 -c 'import time; time.sleep(10**9)' >"$fifo" &
holder_pid=$!
sleep 0.2

(
  cd "$SERVER"
  exec "${java_prefix[@]}" java -Xms512M -Xmx2G \
    -Dfile.encoding=UTF-8 \
    -Dmielib.genhexbook.hexdoc="$HEXDOC" \
    -Dmielib.genhexbook.siteUrl="$SITE_URL" \
    -Dmielib.genhexbook.timeoutSeconds="$TIMEOUT" \
    -jar fabric-server-launch.jar nogui
) <"$fifo" >"$SERVER/logs/console.out" 2>&1 &
server_pid=$!

echo "waiting for the server (max ${START_TIMEOUT}s) ..."
ready=0
for _ in $(seq 1 "$START_TIMEOUT"); do
  if log_has "Done ("; then
    ready=1
    break
  fi
  if log_has "You need to agree to the EULA"; then
    echo "server refused the EULA" >&2
    dump_logs
    exit 1
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "server exited during startup" >&2
    dump_logs
    exit 1
  fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo "timed out waiting for the server" >&2
  dump_logs
  exit 1
fi

echo "running genHexBook export hexbook both no"
rcon_rc=1
for _ in $(seq 1 30); do
  set +e
  python3 "$RCON_PY" --command "genHexBook export hexbook both no" --timeout "$((TIMEOUT + 120))"
  rcon_rc=$?
  set -e
  if [ "$rcon_rc" -eq 0 ]; then
    break
  fi
  if [ "$rcon_rc" -ne 2 ]; then
    echo "rcon command failed ($rcon_rc)" >&2
    dump_logs
    exit 1
  fi
  sleep 2
done
if [ "$rcon_rc" -ne 0 ]; then
  echo "rcon never accepted a connection" >&2
  dump_logs
  exit 1
fi

for _ in $(seq 1 30); do
  if log_has "[GenHexBook] done:"; then
    break
  fi
  if log_has "[GenHexBook] failed"; then
    echo "genHexBook failed" >&2
    dump_logs
    exit 1
  fi
  sleep 1
done
if ! log_has "[GenHexBook] done:"; then
  echo "genHexBook finished without a success line" >&2
  dump_logs
  exit 1
fi
if [ ! -f "$SERVER/hexbook/site/index.html" ]; then
  echo "missing $SERVER/hexbook/site/index.html" >&2
  dump_logs
  exit 1
fi

if [ -n "$OUT" ]; then
  rm -rf "$OUT"
  mkdir -p "$OUT"
  cp -a "$SERVER/hexbook/site/." "$OUT/"
  site_dir="$OUT"
else
  site_dir="$SERVER/hexbook/site"
fi
# Resource ids such as hexcasting:interop become directories. GitHub artifact
# upload and GitHub Pages reject ":"; rewrite those segments to "_".
python3 "$HERE/sanitize_site_paths.py" "$site_dir"
[ -f "$site_dir/index.html" ]
echo "site: $site_dir"
