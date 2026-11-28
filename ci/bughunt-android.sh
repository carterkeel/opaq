#!/usr/bin/env bash
# Bug hunt probes of the Android host layer, on the CI emulator (store-screenshots.yml, set
# bughunt-android). Made-up people only; the debug app talks only to a relay nothing answers.
# Everything lands in store-screens/: bh-*.png screenshots and bughunt.txt (dumpsys and logcat).
set -uo pipefail
pkg=me.opaq.app.debug
act=$pkg/app.opaq.MainActivity
runner=$pkg.test/androidx.test.runner.AndroidJUnitRunner
out=store-screens
rep=$out/bughunt.txt
mkdir -p "$out"
: > "$rep"

./gradlew :androidApp:installDebug :androidApp:installDebugAndroidTest --stacktrace || exit 1

note() { printf '\n===== %s =====\n' "$*" | tee -a "$rep"; }
say() { printf '%s\n' "$*" | tee -a "$rep"; }
shot() { adb exec-out screencap -p > "$out/bh-$1.png" 2>/dev/null || true; }
fix() { for _ in 1 2 3; do adb emu geo fix -122.42690 37.75960 >/dev/null 2>&1; sleep 1; done; }
alive() { say "pid: $(adb shell pidof $pkg | tr -d '\r')"; }
services() {
  say "-- services:"
  adb shell dumpsys activity services "$pkg" | grep -E "ServiceRecord|isForeground|startRequested|restartCount|restartDelay|nextRestartTime|foregroundId" | head -20 | tee -a "$rep"
}
notifs() {
  say "-- our notifications:"
  adb shell dumpsys notification --noredact | awk -v p="pkg=$pkg" '
    /NotificationRecord\(/ { show = index($0, p) > 0 }
    show && (/NotificationRecord\(/ || /android.title=/ || /android.text=/ || /flags=/ || /channel=/) { print }' | head -30 | tee -a "$rep"
}
locreq() {
  say "-- location requests:"
  adb shell dumpsys location | grep -iE "$pkg|Interval|interval=" | grep -i -B1 -A1 "$pkg" | head -20 | tee -a "$rep"
}
logs() {
  say "-- logcat ($1):"
  adb logcat -d -v time | grep -E "FATAL|AndroidRuntime|Exception|Opaq|LocationService|ForegroundService|$pkg" | grep -vE "OpaqStore|ActivityTaskManager: Displayed" | head -${2:-40} | tee -a "$rep"
  adb logcat -c || true
}
run() {
  local o
  o=$(adb shell am instrument -w -e storeScreenshots 1 "${@:2}" -e class "app.opaq.StoreScreenshots#$1" "$runner" 2>&1)
  printf '%s\n' "$o" | tail -5
  grep -q "OK (1 test)" <<< "$o"
}
grant() { for perm in "$@"; do adb shell pm grant "$pkg" "android.permission.$perm" || true; done; }
fresh() {
  adb shell pm clear "$pkg" >/dev/null
  grant ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS ACCESS_BACKGROUND_LOCATION
  adb shell "run-as $pkg sh -c 'mkdir -p files && echo ws://127.0.0.1:9 > files/test-relay'"
  fix
  run seed "$@" || say "seed failed"
  adb shell am force-stop "$pkg"
}
launch() { adb shell am start -W -n "$act" >/dev/null; sleep "${1:-20}"; fix; }

adb shell wm size 1080x2400
adb shell wm density 420
adb shell cmd uimode night no
say "Android $(adb shell getprop ro.build.version.release | tr -d '\r'), API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"

# ---------------------------------------------------------------------------------------------
note "A. launch with sharing on (no battery optimisation exemption, like a real install)"
fresh
adb logcat -c
launch 25
shot a-map
alive; services; notifs; locreq
logs A

# ---------------------------------------------------------------------------------------------
note "B. swipe the sharing notification away (Android 14 lets users dismiss FGS notifications)"
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell cmd statusbar expand-notifications
sleep 3
shot b-shade-before
adb shell uiautomator dump /sdcard/bh-ui.xml >/dev/null 2>&1
adb exec-out cat /sdcard/bh-ui.xml > "$out/bh-ui.xml" 2>/dev/null
b=$(grep -o 'text="Sharing your location[^"]*"[^>]*bounds="[^"]*"' "$out/bh-ui.xml" | head -1 | grep -o 'bounds="[^"]*"')
if [ -z "$b" ]; then b=$(grep -o 'text="Opaq"[^>]*bounds="[^"]*"' "$out/bh-ui.xml" | head -1 | grep -o 'bounds="[^"]*"'); fi
say "notification text bounds: $b"
if [ -n "$b" ]; then
  read -r x1 y1 x2 y2 <<< "$(echo "$b" | grep -o '[0-9]\+' | tr '\n' ' ')"
  y=$(( (y1 + y2) / 2 ))
  adb shell input swipe 120 "$y" 1000 "$y" 250
  sleep 3
  shot b-shade-after-swipe
fi
adb shell cmd statusbar collapse
sleep 2
notifs; services
say "... 70 s later:"
sleep 70
notifs; services
logs B

# ---------------------------------------------------------------------------------------------
note "C. process killed while in the background (sticky restart of the sharing service)"
pid=$(adb shell pidof $pkg | tr -d '\r')
say "killing $pid"
adb shell run-as "$pkg" kill -9 "$pid" || adb shell kill -9 "$pid" || true
sleep 30
alive; services; notifs
logs C 60

# ---------------------------------------------------------------------------------------------
note "D. background location taken away while in the background"
launch 15
adb shell input keyevent KEYCODE_HOME
sleep 3
adb logcat -c
adb shell pm revoke "$pkg" android.permission.ACCESS_BACKGROUND_LOCATION
sleep 30
alive; services; notifs
logs D 60
say "-- now opened again (foreground):"
launch 15
shot d-reopened
alive; services; notifs
adb shell input keyevent KEYCODE_HOME
sleep 40
say "-- 40 s after going home with only while-in-use:"
alive; services; notifs; locreq
logs D2 40
grant ACCESS_BACKGROUND_LOCATION

# ---------------------------------------------------------------------------------------------
note "E. all location access revoked while sharing in the foreground"
launch 15
adb logcat -c
adb shell pm revoke "$pkg" android.permission.ACCESS_FINE_LOCATION
adb shell pm revoke "$pkg" android.permission.ACCESS_COARSE_LOCATION
sleep 5
alive
launch 15
shot e-no-location
alive; services; notifs
logs E 40
grant ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION

# ---------------------------------------------------------------------------------------------
note "F. malformed and hostile invite links"
adb shell am force-stop "$pkg"
launch 15
adb logcat -c
b64() { printf '%s' "$1" | base64 -w0 | tr '+/' '-_' | tr -d '='; }
long=$(head -c 30000 /dev/zero | tr '\0' 'A')
bad=$(b64 '{"v":2,"k":"zz","s":"0123456789abcdef0123456789abcdef","c":"x","n":"Evil","x":"00"}')
bigjson=$(b64 "{\"v\":2,\"k\":\"02$(head -c 64 /dev/zero | tr '\0' 'a')\",\"s\":\"$(head -c 20000 /dev/zero | tr '\0' 'b')\"}")
i=0
for link in \
  "opaq://join#" \
  "opaq://join" \
  "opaq://join/$long" \
  "https://opaq.me/join#$bad" \
  "opaq://join#$bigjson" \
  "https://carterkeel.github.io/opaq/join#%E0%A4%A%%%23%2523" \
  "opaq://join#$(printf 'A%.0s' $(seq 1 60))<b>x</b>;&x=%00" \
  ; do
  i=$((i + 1))
  say "link $i: ${link:0:80}... (${#link} chars)"
  adb shell am start -W -a android.intent.action.VIEW -d "'$link'" "$pkg" 2>&1 | grep -E "Status|Error|Activity" | tee -a "$rep"
  sleep 4
  shot "f-link$i"
  alive
  adb shell input keyevent KEYCODE_BACK
  sleep 1
done
say "explicit intent with a content:// URI:"
adb shell am start -W -n "$act" -d "content://media/external/images/media/1" 2>&1 | grep -E "Status|Error" | tee -a "$rep"
sleep 4; shot f-content; alive
logs F 60

# ---------------------------------------------------------------------------------------------
note "G. the only circle paused on its own: what the notification and GPS do"
fresh -e pausedCircle 1
adb logcat -c
launch 25
shot g-paused-map
adb shell cmd statusbar expand-notifications
sleep 3
shot g-paused-shade
adb shell cmd statusbar collapse
alive; services; notifs; locreq
logs G

# ---------------------------------------------------------------------------------------------
note "H. storage full"
adb shell am force-stop "$pkg"
adb shell "run-as $pkg sh -c 'cd files; for s in 512M 64M 8M 1M 128K 16K; do i=0; while fallocate -l \$s fill-\$s-\$i 2>/dev/null; do i=\$((i+1)); done; done; ls | wc -l'" | tee -a "$rep"
adb shell df -h /data | tail -1 | tee -a "$rep"
adb logcat -c
launch 25
fix
sleep 20
alive
shot h-full
logs H 60
adb shell "run-as $pkg sh -c 'rm -f files/fill-*'"
adb shell df -h /data | tail -1 | tee -a "$rep"

adb logcat -d -v time > store-logcat.txt || true
ls -la "$out"
exit 0
