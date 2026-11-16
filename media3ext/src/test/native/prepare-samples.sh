#!/usr/bin/env bash
set -euo pipefail
out=${1:-/tmp/nextlib-samples}
mkdir -p "$out"
# Synthetic sample plus public FFmpeg regression samples. Media files are not committed.
ffmpeg -v error -f lavfi -i testsrc2=size=160x128:rate=15 \
  -f lavfi -i sine=frequency=440:sample_rate=44100 -t 8 \
  -c:v rv10 -c:a ac3 -y "$out/rv10.rm"
curl -fL --retry 3 https://samples.ffmpeg.org/real/VC-RV30/eiqu-56k.rm -o "$out/rv30.rm"
curl -fL --retry 3 https://samples.ffmpeg.org/real/VC-RV40/20040506.rm -o "$out/rv40.rm"
curl -fL --retry 3 https://samples.ffmpeg.org/real/VC-RV30/141a.rm -o "$out/rv30-clip.rm"
curl -fL --retry 3 https://samples.ffmpeg.org/real/VC-RV40/Blade2_640_1Mbps.rm -o "$out/rv40-clip.rm"
curl -fL --retry 3 https://samples.ffmpeg.org/real/AC-raac/HeyYa_RA10_AAC_128K_30s.rm -o "$out/aac.rm"
