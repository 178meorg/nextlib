#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a disposable Android device/emulator}"
repo=$(cd "$(dirname "$0")/../../../.." && pwd)
build=$(mktemp -d)
remote=
cleanup() {
    if [[ -n "$remote" ]]; then adb -s "$ANDROID_SERIAL" shell rm -rf "$remote"; fi
    rm -rf "$build"
}
trap cleanup EXIT
abi=$(adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')
case "$abi" in
    x86) target=i686-linux-android23 ;;
    x86_64) target=x86_64-linux-android23 ;;
    armeabi-v7a) target=armv7a-linux-androideabi23 ;;
    arm64-v8a) target=aarch64-linux-android23 ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
esac
case "$(uname -s)" in
    Linux) host=linux-x86_64 ;;
    Darwin) host=darwin-x86_64 ;;
    *) exit 1 ;;
esac
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host/bin/clang++" \
    --target="$target" -std=c++17 -O2 -static-libstdc++ \
    -Wl,-z,max-page-size=16384 -I"$repo/ffmpeg/output/include/$abi" \
    "$repo/media3ext/src/test/cpp/vc1_decoder_test.cpp" \
    -L"$repo/ffmpeg/output/lib/$abi" -lavcodec -lavutil \
    -o "$build/vc1_decoder_test"
remote=$(adb -s "$ANDROID_SERIAL" shell mktemp -d /data/local/tmp/nextlib-vc1.XXXXXX | tr -d '\r')
adb -s "$ANDROID_SERIAL" push "$build/vc1_decoder_test" \
    "$repo"/ffmpeg/output/lib/"$abi"/*.so "$remote/" >/dev/null
adb -s "$ANDROID_SERIAL" shell "cd '$remote' && LD_LIBRARY_PATH=. ./vc1_decoder_test"
