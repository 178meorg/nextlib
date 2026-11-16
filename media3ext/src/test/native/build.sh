#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../../../.." && pwd)
out="$root/build/host"
mkdir -p "$out"
jdk=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
# Test against the same FFmpeg release as Android, not the host's potentially incompatible version.
if [[ ! -f "$out/ffmpeg/.complete" ]]; then
  if [[ ! -d "$out/ffmpeg-6.0" ]]; then
    curl -fL --retry 3 https://ffmpeg.org/releases/ffmpeg-6.0.tar.gz -o "$out/ffmpeg.tar.gz"
    tar -xzf "$out/ffmpeg.tar.gz" -C "$out"
  fi
  (
    cd "$out/ffmpeg-6.0"
    ./configure --prefix="$out/ffmpeg" --disable-everything --disable-autodetect \
      --disable-asm --enable-shared --disable-static --disable-programs --disable-doc \
      --disable-avdevice --disable-avfilter --disable-postproc --disable-network \
      --enable-demuxer=rm --enable-protocol=file --enable-parsers \
      --enable-decoder=rv10,rv20,rv30,rv40,cook,sipr,ra_144,ra_288,atrac3,aac,ac3,mp3 \
      --extra-ldflags="-Wl,-rpath,$out/ffmpeg/lib"
    make -j8
    make install
    touch "$out/ffmpeg/.complete"
  )
fi
c++ -std=c++17 -fPIC -shared -Wall -Wextra -Wno-unused-parameter \
  -I"$jdk/include" -I"$jdk/include/linux" -I"$out/ffmpeg/include" \
  -I"$root/media3ext/src/test/native" \
  "$root/media3ext/src/main/cpp/ffextractor.cpp" \
  "$root/media3ext/src/main/cpp/ffmain.cpp" \
  "$root/media3ext/src/main/cpp/ffcommon.cpp" \
  "$root/media3ext/src/main/cpp/ffaudio.cpp" \
  "$root/media3ext/src/main/cpp/ffvideo.cpp" \
  -L"$out/ffmpeg/lib" -Wl,-rpath,"$out/ffmpeg/lib" \
  -lavformat -lavcodec -lavutil -lswscale -lswresample \
  -o "$out/libnextlib_demux_test.so"
