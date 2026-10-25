#!/usr/bin/env bash
set -uo pipefail
mkdir -p editor-evidence
collect() {
  adb logcat -d -v threadtime > editor-evidence/logcat.txt 2>&1 || true
  adb shell dumpsys meminfo com.markreader > editor-evidence/device-meminfo.txt 2>&1 || true
  adb shell getprop > editor-evidence/device-properties.txt 2>&1 || true
  adb exec-out run-as com.markreader cat files/editor-metrics.jsonl > editor-evidence/last-metrics.jsonl 2>/dev/null || true
  python3 scripts/editor-metrics.py editor-evidence || true
}
trap collect EXIT
adb wait-for-device
adb shell input keyevent 82
adb logcat -c
# No storage/notification permission is required: fixtures and metrics are app-private.
# Grant-at-install is used by AGP; do not attempt legacy WRITE_EXTERNAL_STORAGE on API35.
./gradlew :app:connectedDebugAndroidTest --stacktrace --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8' \
  -Pandroid.testInstrumentationRunnerArguments.class=com.markreader.ui.editor.NativeEditorInputTest
native_status=$?
adb exec-out run-as com.markreader cat files/editor-metrics.jsonl > editor-evidence/native-metrics.jsonl 2>/dev/null || true
cp -R app/build/outputs/androidTest-results editor-evidence/native-xml 2>/dev/null || true
cp -R app/build/reports/androidTests editor-evidence/native-report 2>/dev/null || true
baseline_status=0
if [ "$native_status" -eq 0 ] && [ "${RUN_BASELINE:-false}" = true ]; then
  adb shell run-as com.markreader rm -f files/editor-metrics.jsonl
  ./gradlew :app:connectedDebugAndroidTest --stacktrace --max-workers=2 \
    '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8' \
    -Pandroid.testInstrumentationRunnerArguments.class=com.markreader.ui.editor.BasicTextFieldBaselineTest
  baseline_status=$?
  adb exec-out run-as com.markreader cat files/editor-metrics.jsonl > editor-evidence/baseline-metrics.jsonl 2>/dev/null || true
fi
printf 'native_exit=%s\nbaseline_exit=%s\n' "$native_status" "$baseline_status" > editor-evidence/runner-status.txt
if [ "$native_status" -ne 0 ]; then exit "$native_status"; fi
exit "$baseline_status"
