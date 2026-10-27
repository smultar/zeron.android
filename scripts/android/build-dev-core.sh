#!/usr/bin/env bash
# Builds the core for apps/android-dev: the shared build-core.sh at the small `mobile-android`
# profile, the bindings patched for that app, then the libraries stripped for the APK.
# (build-core.sh reads the UniFFI metadata out of the .so, so it can only be stripped afterwards.)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-$ROOT/target/android-core}"
export ZERON_ANDROID_PROFILE="${ZERON_ANDROID_PROFILE:-mobile-android}"
"$ROOT/scripts/android/build-core.sh" "$OUT"
python3 "$ROOT/scripts/android/fix-bindings.py" "$OUT/kotlin/uniffi/zeron_core/zeron_core.kt" 2>/dev/null \
  || python "$ROOT/scripts/android/fix-bindings.py" "$OUT/kotlin/uniffi/zeron_core/zeron_core.kt"
NDK="${ANDROID_NDK_HOME:-}"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) TAG=windows-x86_64; EXE=.exe ;; Darwin) TAG=darwin-x86_64; EXE= ;; *) TAG=linux-x86_64; EXE= ;; esac
for so in "$OUT"/jniLibs/*/libzeron_mobile.so; do
  "$NDK/toolchains/llvm/prebuilt/$TAG/bin/llvm-strip$EXE" --strip-unneeded "$so"
done
echo "dev core ready: $OUT"
