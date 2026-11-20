#!/usr/bin/env bash
# A Nostr relay on this computer for testing with simulators and emulators, so test devices
# never use public relays (which rate-limit, and can ban the whole household's address when
# several test devices share it).
#
#   brew install nak          # once
#   tools/test-relay.sh       # leave it running; Ctrl-C to stop
#
# Then point debug builds at it (release and staging builds ignore this):
#   iOS simulator:     xcrun simctl spawn booted defaults write app.opaq opaqTestRelay ws://localhost:7777
#   Android emulator:  adb shell run-as app.opaq.debug sh -c 'echo ws://10.0.2.2:7777 > files/test-relay'
# and restart the app. Settings → Connection shows "Test relay". To go back to the saved relays:
#   xcrun simctl spawn booted defaults delete app.opaq opaqTestRelay
#   adb shell run-as app.opaq.debug rm files/test-relay
#
# The relay keeps events in memory. This script saves them every minute (and when it stops) to
# $OPAQ_TEST_RELAY_DATA, and loads them again on start, so circles survive a restart.
set -euo pipefail

PORT="${PORT:-7777}"
DATA="${OPAQ_TEST_RELAY_DATA:-$HOME/.opaq-test-relay/events.jsonl}"
URL="ws://127.0.0.1:$PORT"

command -v nak >/dev/null || { echo "Needs nak: brew install nak" >&2; exit 1; }
mkdir -p "$(dirname "$DATA")"
touch "$DATA"

# 127.0.0.1 only: the simulators reach it as localhost, the Android emulator as 10.0.2.2.
# Its log prints every event, so it goes to a file next to the saved events.
# nak serve stops answering once its stdin closes, as it does for a background job, so its stdin is
# a pipe this script holds open.
FIFO="$DATA.stdin"
rm -f "$FIFO"
mkfifo "$FIFO"
nak serve --hostname 127.0.0.1 --port "$PORT" --events "$DATA" < "$FIFO" > "$DATA.log" 2>&1 &
RELAY=$!
exec 3> "$FIFO"

save() {
  # Everything the relay holds, newest first; written to a temporary file so a failed dump
  # never replaces a good one.
  # One request with a high limit: nak's --paginate can loop forever and jam the relay.
  # The watchdog stops a dump that hangs, so the loop and Ctrl-C never get stuck.
  nak req -l 1000000 "$URL" > "$DATA.tmp" 2>/dev/null &
  local dump=$! ok=0
  ( sleep 20; kill "$dump" 2>/dev/null ) &
  local watchdog=$!
  wait "$dump" && ok=1
  kill "$watchdog" 2>/dev/null || true
  if [ "$ok" = 1 ] && [ -s "$DATA.tmp" ]; then
    mv "$DATA.tmp" "$DATA"
  else
    rm -f "$DATA.tmp"
  fi
}

stop() {
  trap - EXIT INT TERM
  save
  kill "$RELAY" 2>/dev/null || true
  exec 3>&-
  rm -f "$FIFO"
  exit 0
}
trap stop EXIT INT TERM

echo "Test relay on $URL ($(wc -l < "$DATA" | tr -d ' ') saved events loaded)"
while kill -0 "$RELAY" 2>/dev/null; do
  # In the background, so Ctrl-C saves and stops at once rather than after the sleep.
  sleep 60 & wait $!
  save
done
