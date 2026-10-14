# VC-1 integration and verification

This fork targets Media3 1.8.0. The publication coordinates are
`io.github.178meorg:nextlib-media3ext:1.8.0-0.16.0-vc1.1` (and
`nextlib-mediainfo` for the media-info module). The release AARs are built locally;
these coordinates do not imply availability on Maven Central.

## Initialization data

Only `MimeTypes.VIDEO_VC1` (`video/wvc1`) maps to FFmpeg's `vc1` decoder.
WMV3 and vendor MIME aliases are not mapped or enabled.

Media3 1.8.0's [Matroska extractor](https://github.com/androidx/media/blob/1.8.0/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java)
handles WVC1 FourCC codec-private data by locating the `00 00 01 0F` sequence header
after the BITMAPINFOHEADER and preserving everything from that point onward.
The library concatenates initialization buffers in their original order, retaining
sequence headers, entry points, escape bytes, and trailing data. No artificial
sequence header, size prefix, or entry point is synthesized.

[FFmpeg 9.0.1](https://github.com/FFmpeg/FFmpeg/blob/n9.0.1/libavcodec/vc1dec.c)
requires at least 16 extradata bytes and both sequence and entry-point headers
at decoder initialization. The Java path rejects missing data, empty header
payloads, and missing or out-of-order required headers. The renderer returns
`FORMAT_UNSUPPORTED_SUBTYPE` for these cases. Payload bitstream validity remains
FFmpeg's responsibility: structurally complete headers can still fail native
initialization. The existing JNI path copies extradata with FFmpeg's required
zero padding and reuses it when recreating the decoder after reset.

This change does not add container extractors. In particular, MKV extractor support
does not establish support for VC-1 inside every TS, ASF/WMV, or MP4 file.

## Completed checks (2026-10-14)

- Release native builds: x86, x86_64, armeabi-v7a, arm64-v8a.
- Release AARs built for both modules; media3ext contains JNI and FFmpeg libraries
  for all four ABIs.
- Both modules published to the local Maven repository with source and Javadoc
  jars; media3ext publication metadata declares Media3 1.8.0.
- 17 JVM tests passed, including MIME mapping and initialization-data handling.
- Setup regression checks passed, including enabling VC-1 and excluding WMV3.
- Android 15 (API 35), x86_64 emulator: the actual output `libavcodec.so` registers
  VC-1 by both name and codec ID, excludes WMV3, and rejects missing extradata.
- On the same emulator, `vc1RequiresHeadersAndBundledDecoder` passed, confirming
  native availability and renderer rejection of missing initialization headers.

The JVM header fixtures test byte preservation and structural rejection. They
are not playable VC-1 bitstreams and do not prove successful decoding.

To repeat the native registry check on another ABI/device:

```sh
ANDROID_NDK_HOME=/path/to/sdk/ndk/25.2.9519653 ANDROID_SERIAL=device-id \
  bash media3ext/src/test/cpp/run_vc1_decoder_test.sh
```

## Outstanding playback and App checks

No VC-1 sample or App source was supplied. The following remain unverified:

| Scenario | Required observation |
| --- | --- |
| First playback | Valid headers initialize the decoder and a visible first frame appears |
| Resume from a saved position | Decoding recovers at the selected position |
| Repeated forward/backward seeking | Reset/flush causes no corruption, hang, or resource leak |
| Continuous playback | Audio/video synchronization, dropped frames, and CPU use are acceptable |
| Interlaced content | Verify field handling and display quality; no deinterlacing was added |
| No platform decoder | The App selects FFmpeg with extension renderers enabled |
| Hardware decoder failure | The App's error recovery selects FFmpeg and resumes playback |

The existing decode, frame conversion, and Surface output pipeline is reused.
Renderer priority and MediaCodec initialization fallback do not guarantee recovery
from every runtime hardware failure. The App must verify its error recovery.

## Publication

Use `./gradlew publishToMavenLocal -PreleaseSigningEnabled=false` for local
integration when signing keys are unavailable. Signing remains enabled by default
for remote release publication. The consuming App
must add `mavenLocal()` and keep all Media3 modules on 1.8.0.
Remote Maven Central publication requires credentials, a signing key, and access
to the `io.github.178meorg` namespace; none were configured in this environment.

The GitHub publishing workflow now targets Cloudsmith `178meorg/nextlib` using
`CLOUDSMITH_USERNAME` and `CLOUDSMITH_API_KEY` repository Secrets. GPG signing is
disabled for this target. The public download endpoint is
`https://dl.cloudsmith.io/public/178meorg/nextlib/maven/`; anonymous downloads depend
on the repository being public. Configuring the target does not establish that
the remote publication has completed.
