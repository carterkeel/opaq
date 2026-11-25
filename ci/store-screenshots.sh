#!/usr/bin/env bash
# Takes the Google Play screenshots (androidTest/StoreScreenshots.kt) on the CI emulator, with
# made-up people in San Francisco. Run by .github/workflows/store-screenshots.yml; the PNGs land
# in store-screens/ at 1080 x 1920 (9:16).
set -euo pipefail
pkg=me.opaq.app.debug
runner=$pkg.test/androidx.test.runner.AndroidJUnitRunner

# A 1080 x 1920 phone, whatever the emulator's own screen is.
adb shell wm size 1080x1920
adb shell wm density 420

./gradlew :androidApp:installDebug :androidApp:installDebugAndroidTest --stacktrace

adb shell pm clear "$pkg" >/dev/null
for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS; do
  adb shell pm grant "$pkg" "android.permission.$perm" || true
done
adb shell dumpsys deviceidle whitelist "+$pkg" >/dev/null || true
# Nothing leaves the emulator: the debug app uses only this relay, and nothing answers there.
# (adb shell joins its arguments into one remote command line, so the quoting is for that shell.)
adb shell "run-as $pkg sh -c 'mkdir -p files && echo ws://127.0.0.1:9 > files/test-relay'"
adb shell "run-as $pkg cat files/test-relay"
# "You" are at Mission Dolores Park.
for _ in 1 2 3; do adb emu geo fix -122.42690 37.75960 >/dev/null; sleep 1; done

run() {
  local out
  out=$(adb shell am instrument -w -e storeScreenshots 1 -e class "app.opaq.StoreScreenshots#$1" "$runner" 2>&1)
  printf '%s\n' "$out"
  grep -q "OK (1 test)" <<< "$out"
}

run seed
adb shell am force-stop "$pkg"

# A tidy status bar: 9:41, full signal and battery, no notification icons.
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile show -e datatype none -e level 4 -e fully true
demo notifications -e visible false

for _ in 1 2 3; do adb emu geo fix -122.42690 37.75960 >/dev/null; sleep 1; done
status=0
run capture || status=$?

demo exit || true
rm -rf store-screens && mkdir -p store-screens
dir=/sdcard/Download/opaq-store
for f in $(adb shell ls "$dir" 2>/dev/null | tr -d '\r'); do
  adb exec-out cat "$dir/$f" > "store-screens/$f"
done
ls -la store-screens
adb logcat -d -v time > store-logcat.txt || true
exit $status
