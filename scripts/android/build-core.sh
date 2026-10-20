#!/bin/bash
# Build the Rust mobile core (crates/mobile) for Android and generate its
# Kotlin bindings — the same library the iOS app links.
#
#   scripts/android/build-core.sh [out_dir]
#
# Outputs (default target/android-core/), consumed by apps/android:
#   kotlin/        UniFFI bindings (patched by fix-bindings.py)
#   jniLibs/<abi>/ libzeron_mobile.so (stripped) + libc++_shared.so
#
# Needs the Android NDK (ANDROID_NDK_HOME, or ANDROID_HOME/ndk/<version>),
# cargo-ndk (`cargo install cargo-ndk`) and the Rust targets
# (`rustup target add aarch64-linux-android x86_64-linux-android`).
# Works from Linux/macOS shells and Git Bash on Windows.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# ANDROID_PROFILE=mobile-android builds the small, fully optimized core for release APKs.
OUT="${1:-$ROOT/target/android-core}"
mkdir -p "$OUT/kotlin" "$OUT/jniLibs"
cd "$ROOT"

case "$(uname -s)" in
  Darwin) HOST_LIB=libzeron_mobile.dylib; HOST_TAG=darwin-x86_64; EXE= ;;
  MINGW*|MSYS*|CYGWIN*) HOST_LIB=zeron_mobile.dll; HOST_TAG=windows-x86_64; EXE=.exe ;;
  *) HOST_LIB=libzeron_mobile.so; HOST_TAG=linux-x86_64; EXE= ;;
esac

cargo build --locked -p zeron-mobile --lib --profile mobile
cargo build --locked -p zeron-mobile --bin uniffi-bindgen --features bindgen --profile mobile
"$ROOT/target/mobile/uniffi-bindgen$EXE" generate --library "$ROOT/target/mobile/$HOST_LIB" \
  --language kotlin --no-format --out-dir "$OUT/kotlin"
python3 "$ROOT/scripts/android/fix-bindings.py" "$OUT/kotlin/uniffi/zeron_core/zeron_core.kt" 2>/dev/null \
  || python "$ROOT/scripts/android/fix-bindings.py" "$OUT/kotlin/uniffi/zeron_core/zeron_core.kt"
echo "kotlin bindings: $OUT/kotlin"

NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" ]]; then NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort | tail -n1)"; fi
if command -v cargo-ndk >/dev/null && [[ -n "$NDK" ]]; then
  export ANDROID_NDK_HOME="$NDK"
  cargo ndk -t arm64-v8a -t x86_64 -o "$OUT/jniLibs" build --locked -p zeron-mobile --lib --profile "${ANDROID_PROFILE:-mobile}"
  LLVM="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
  for abi in arm64-v8a:aarch64-linux-android x86_64:x86_64-linux-android; do
    dir="$OUT/jniLibs/${abi%%:*}"
    # The core links libc++ dynamically; ship the NDK's copy next to it.
    cp "$LLVM/sysroot/usr/lib/${abi##*:}/libc++_shared.so" "$dir/"
    # The `mobile` profile keeps line tables (~250 MB); the APK doesn't need them.
    "$LLVM/bin/llvm-strip$EXE" --strip-unneeded "$dir/libzeron_mobile.so"
  done
  echo "jniLibs: $OUT/jniLibs"
else
  echo "note: cargo-ndk / the Android NDK not found — skipped the .so build"
fi
