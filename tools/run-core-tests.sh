#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KOTLINC_BIN="${KOTLINC:-$(command -v kotlinc || true)}"
if [[ -z "$KOTLINC_BIN" ]]; then
  echo "Kotlin compiler not found. Set KOTLINC or use ./gradlew :core-model:test :streaming:testDebugUnitTest instead." >&2
  exit 1
fi
WORK="${TMPDIR:-/tmp}/mobile-streaming-studio-core-tests"
rm -rf "$WORK"
mkdir -p "$WORK"
mapfile -t MODEL_SOURCES < <(find "$ROOT/core-model/src/main/kotlin" -name '*.kt' -print | sort)
mapfile -t RTMP_SOURCES < <(find "$ROOT/streaming/src/main/kotlin/com/mobilestreaming/studio/streaming/rtmp" -name '*.kt' -print | sort)
"$KOTLINC_BIN" \
  "${MODEL_SOURCES[@]}" \
  "${RTMP_SOURCES[@]}" \
  "$ROOT/tools/core-tests/CoreTests.kt" \
  -include-runtime -d "$WORK/core-tests.jar"
java -jar "$WORK/core-tests.jar"
