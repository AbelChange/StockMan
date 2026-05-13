#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mkdir -p run-logs

collect_pids() {
  {
    lsof -ti tcp:8080
    lsof -ti tcp:8081
    pgrep -f "com\\.liaobusi\\.stockman\\.monitor\\.ServerKt"
    pgrep -f "$ROOT_DIR/.+server/build/install/server/bin/server"
    pgrep -f "$ROOT_DIR/.+:web:jsBrowserDevelopmentRun"
    pgrep -f "$ROOT_DIR/.+webpack"
  } 2>/dev/null | sort -u | grep -v "^$$$" || true
}

stop_screens() {
  (screen -ls 2>/dev/null || true) |
    awk '/[0-9]+\.stockman-(server|web)[[:space:]]/ { print $1 }' |
    while read -r session; do
      screen -S "$session" -X quit >/dev/null 2>&1 || true
    done
  screen -wipe >/dev/null 2>&1 || true
}

wait_for_exit() {
  local deadline=$((SECONDS + 8))
  while (( SECONDS < deadline )); do
    if [[ -z "$(collect_pids)" ]]; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

stop_existing() {
  stop_screens

  local pids
  pids="$(collect_pids)"
  if [[ -n "$pids" ]]; then
    echo "stopping old processes: $pids"
    kill $pids 2>/dev/null || true
  fi

  if ! wait_for_exit; then
    pids="$(collect_pids)"
    if [[ -n "$pids" ]]; then
      echo "force stopping old processes: $pids"
      kill -9 $pids 2>/dev/null || true
    fi
  fi

  if [[ -n "$(collect_pids)" ]]; then
  echo "failed to stop existing stockman-monitor processes" >&2
  exit 1
  fi
  stop_screens
}

wait_for_port() {
  local port="$1"
  local deadline=$((SECONDS + 30))
  while (( SECONDS < deadline )); do
    if lsof -ti tcp:"$port" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  echo "warning: port $port is not listening yet" >&2
  return 1
}

stop_existing

./gradlew --no-daemon :server:installDist > run-logs/server-build.log 2>&1
screen -dmS stockman-server bash -lc "cd '$ROOT_DIR' && server/build/install/server/bin/server > run-logs/server.log 2>&1"
screen -dmS stockman-web bash -lc "cd '$ROOT_DIR' && ./gradlew --no-daemon -Dkotlin.daemon.jvm.options=-Xmx2g :web:jsBrowserDevelopmentRun > run-logs/web.log 2>&1"

wait_for_port 8080 || true
wait_for_port 8081 || true

echo "server: http://localhost:8080"
echo "db:     http://localhost:8080/db"
echo "web:    http://localhost:8081"
echo "logs:   run-logs/server-build.log run-logs/server.log run-logs/web.log"
