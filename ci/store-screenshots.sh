#!/usr/bin/env bash
# Takes the Google Play screenshots (androidTest/StoreScreenshots.kt) on the CI emulator, with
# made-up people in San Francisco. Run by .github/workflows/store-screenshots.yml; the PNGs land
# in store-screens/.
#
# SCREENSHOT_SET picks the screens:
#   phone     1080 x 1920 (9:16), every shot
#   foldable  an unfolded screen at 1:1 and 4:3 (and 3:4), and the outer screen at 10:16
set -euo pipefail
pkg=me.opaq.app.debug
runner=$pkg.test/androidx.test.runner.AndroidJUnitRunner
dir=/sdcard/Download/opaq-store

# size:density:file prefix:shots (comma-separated names from StoreScreenshots.capture, or all)
case "${SCREENSHOT_SET:-phone}" in
  phone) configs=("1080x1920:420:phone:all") ;;
  foldable) configs=(
    "1840x1840:420:fold-1x1:map,dark-people"
    "2208x1656:420:fold-4x3:map,dark-map"
    "1080x1728:420:fold-10x16:map,profile"
    "1656x2208:420:fold-3x4:history,dark-place"
  ) ;;
  *) echo "Unknown SCREENSHOT_SET ${SCREENSHOT_SET}"; exit 1 ;;
esac

./gradlew :androidApp:installDebug :androidApp:installDebugAndroidTest --stacktrace
adb shell rm -rf "$dir" || true

run() {
  local out
  out=$(adb shell am instrument -w -e storeScreenshots 1 "${@:2}" -e class "app.opaq.StoreScreenshots#$1" "$runner" 2>&1)
  printf '%s\n' "$out"
  grep -q "OK (1 test)" <<< "$out"
}
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
adb shell settings put global sysui_demo_allowed 1

status=0
for config in "${configs[@]}"; do
  IFS=: read -r size density prefix shots <<< "$config"
  echo "=== $prefix: $size at $density dpi, shots: $shots ==="
  adb shell wm size "$size"
  adb shell wm density "$density"
  sleep 3

  # Fresh made-up state for each screen, so moving people still read "just now".
  adb shell pm clear "$pkg" >/dev/null
  for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS; do
    adb shell pm grant "$pkg" "android.permission.$perm" || true
  done
  adb shell dumpsys deviceidle whitelist "+$pkg" >/dev/null || true
  # Nothing leaves the emulator: the debug app uses only this relay, and nothing answers there.
  # (adb shell joins its arguments into one remote command line, so the quoting is for that shell.)
  adb shell "run-as $pkg sh -c 'mkdir -p files && echo ws://127.0.0.1:9 > files/test-relay'"
  # "You" are at Mission Dolores Park.
  for _ in 1 2 3; do adb emu geo fix -122.42690 37.75960 >/dev/null; sleep 1; done

  run seed || { status=1; continue; }
  adb shell am force-stop "$pkg"

  # A tidy status bar: 9:41, full signal and battery, no notification icons.
  demo enter
  demo clock -e hhmm 0941
  demo battery -e level 100 -e plugged false
  demo network -e wifi show -e level 4 -e fully true
  demo network -e mobile show -e datatype none -e level 4 -e fully true
  demo notifications -e visible false

  for _ in 1 2 3; do adb emu geo fix -122.42690 37.75960 >/dev/null; sleep 1; done
  run capture -e shots "$shots" -e prefix "$prefix" || status=1
  demo exit || true
done

rm -rf store-screens && mkdir -p store-screens
for f in $(adb shell ls "$dir" 2>/dev/null | tr -d '\r'); do
  adb exec-out cat "$dir/$f" > "store-screens/$f"
done
ls -la store-screens
adb logcat -d -v time > store-logcat.txt || true
exit $status
