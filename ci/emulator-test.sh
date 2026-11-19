#!/usr/bin/env bash
# Runs the instrumented UI test on the CI emulator and always saves logcat, so crashes
# (stack traces under "FATAL EXCEPTION") end up in the ci-logs summary.
#
# Emulators on shared CI machines are sometimes slow to settle, so a failed run is retried
# once from a clean app state. A real crash fails both attempts.
set -uo pipefail

# The staging APK is what people install on their phones, so check it installs too. A failed
# install fails the script (after the UI test has run, so its results are still logged).
# Older adb versions print "Failure [...]" but exit 0, so the output is checked as well.
install_failed=0
for apk in androidApp/build/outputs/apk/staging/*.apk; do
  echo "=== Installing $apk ===" | tee -a emulator.log
  install_out=$(adb install -r "$apk" 2>&1)
  install_rc=$?
  printf '%s\n' "$install_out" | tee -a emulator.log
  adb uninstall app.opaq.staging >/dev/null 2>&1 || true
  if [ "$install_rc" -ne 0 ] || grep -q "Failure" <<< "$install_out"; then
    echo "=== Staging install FAILED (adb exit $install_rc): $apk ===" | tee -a emulator.log
    install_failed=1
  fi
done
status=1
for attempt in 1 2; do
  echo "=== UI smoke test, attempt $attempt ===" | tee -a emulator.log
  adb logcat -c || true
  adb shell pm clear app.opaq.debug >/dev/null 2>&1 || true
  adb shell rm -rf /sdcard/Download/opaq-screens >/dev/null 2>&1 || true
  ./gradlew :androidApp:connectedDebugAndroidTest --stacktrace 2>&1 | tee -a emulator.log
  status=$?
  adb logcat -d -v time >> logcat.txt || true
  # Screenshots the test took (light and dark), for checking screens by eye in the CI summary.
  rm -rf screens && mkdir -p screens
  dir=/sdcard/Download/opaq-screens
  for f in $(adb shell ls "$dir" 2>/dev/null | tr -d '\r'); do
    adb exec-out cat "$dir/$f" > "screens/$f" 2>/dev/null || true
  done
  echo "=== Screenshots: $(ls screens | wc -l) ===" | tee -a emulator.log
  [ $status -eq 0 ] && break
done
if [ "$install_failed" -ne 0 ]; then
  echo "=== FAILED: the staging APK did not install (see above) ===" | tee -a emulator.log
  [ $status -eq 0 ] && status=1
fi
exit $status
