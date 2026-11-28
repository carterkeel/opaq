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
    # Densities keep the shorter side under 600 dp, where Android would pin its taskbar (with
    # other apps' icons) along the bottom of every screenshot.
    "1840x1840:500:fold-1x1:map,dark-people"
    "2208x1656:480:fold-4x3:map,dark-map"
    "1080x1728:420:fold-10x16:map,profile"
    "1656x2208:480:fold-3x4:history,dark-place"
  ) ;;
  # A tapped stack's list popping up over the map.
  group) configs=("1080x2400:420:group:map,group") ;;
  # The profile and history screens: the profile's map opening history, the stretches scrolling
  # under the map, and the map full screen.
  history) configs=("1080x2400:420:history:profile,history,history-scrolled,history-full") ;;
  # Setup's location step on a short and a tall phone, to check it fits without scrolling.
  setup) configs=("1080x1920:420:setup-16x9:setup" "1080x2400:420:setup-20x9:setup") ;;
  # The demo video for Play's background location declaration (StoreScreenshots.demo).
  video) configs=("1080x1920:420:video:video") ;;
  *) echo "Unknown SCREENSHOT_SET ${SCREENSHOT_SET}"; exit 1 ;;
esac

./gradlew :androidApp:installDebug :androidApp:installDebugAndroidTest --stacktrace
adb shell rm -rf "$dir" || true
rm -rf store-screens

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
  perms="ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS"
  # Setup's check starts without location access, so it shows the step before it's allowed.
  [ "$shots" = setup ] && perms="POST_NOTIFICATIONS"
  # The video shows allowing background location, so it starts without it.
  [ "$shots" = video ] || perms="$perms ACCESS_BACKGROUND_LOCATION"
  for perm in $perms; do
    adb shell pm grant "$pkg" "android.permission.$perm" || true
  done
  adb shell dumpsys deviceidle whitelist "+$pkg" >/dev/null || true
  # Nothing leaves the emulator: the debug app uses only this relay, and nothing answers there.
  # (adb shell joins its arguments into one remote command line, so the quoting is for that shell.)
  adb shell "run-as $pkg sh -c 'mkdir -p files && echo ws://127.0.0.1:9 > files/test-relay'"
  # "You" are at Mission Dolores Park.
  for _ in 1 2 3; do adb emu geo fix -122.42690 37.75960 >/dev/null; sleep 1; done

  # Setup only shows on a fresh install, so its check isn't seeded.
  if [ "$shots" != setup ]; then run seed || { status=1; continue; }; fi
  adb shell am force-stop "$pkg"

  # A tidy status bar: 9:41, full signal and battery, no notification icons.
  demo enter
  demo clock -e hhmm 0941
  demo battery -e level 100 -e plugged false
  demo network -e wifi show -e level 4 -e fully true
  demo network -e mobile show -e datatype none -e level 4 -e fully true
  for _ in 1 2 3; do adb emu geo fix -122.42690 37.75960 >/dev/null; sleep 1; done
  if [ "$shots" = setup ]; then
    demo notifications -e visible false
    run setup -e prefix "$prefix" || status=1
  elif [ "$shots" = video ]; then
    # The video shows the sharing notification, so notification icons stay on.
    adb shell rm -f /sdcard/Download/opaq-demo.mp4
    adb logcat -c || true
    adb shell date +%s.%N > video-start.txt
    adb shell screenrecord --bit-rate 8000000 --time-limit 170 /sdcard/Download/opaq-demo.mp4 &
    rec=$!
    run demo || status=1
    sleep 1
    adb shell pkill -INT screenrecord || true
    wait "$rec" || true
    sleep 2
    mkdir -p store-screens
    adb exec-out cat /sdcard/Download/opaq-demo.mp4 > store-screens/opaq-demo.mp4
    # Step times, seconds from the start of the recording, for captions.
    start=$(tr -d '\r' < video-start.txt)
    adb logcat -d -v epoch -s OpaqStore:I | awk -v s="$start" '/mark / { printf "%.1f %s\n", $1 - s, $NF }' > store-screens/video-marks.txt
    cat store-screens/video-marks.txt
  else
    demo notifications -e visible false
    run capture -e shots "$shots" -e prefix "$prefix" || status=1
  fi
  demo exit || true
done

mkdir -p store-screens
for f in $(adb shell ls "$dir" 2>/dev/null | tr -d '\r'); do
  adb exec-out cat "$dir/$f" > "store-screens/$f"
done
ls -la store-screens
adb logcat -d -v time > store-logcat.txt || true
exit $status
