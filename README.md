# NextLib

[![Build nextlib](https://github.com/178meorg/nextlib/actions/workflows/build.yaml/badge.svg)](https://github.com/178meorg/nextlib/actions/workflows/build.yaml)

NextLib is a library for adding ffmpeg codecs to [Media3](https://github.com/androidx/media).

## Currently supported decoders
- **Audio**: Vorbis, Opus, Flac, Alac, pcm_mulaw, pcm_alaw, MP3, Amrnb, Amrwb, AAC, AC3, EAC3, dca, mlp, truehd
- **Video**: H.264, HEVC, VP8 and VP9 (FFmpeg built-in decoders), AV1 (dav1d), VC-1 (requires sequence and entry-point initialization headers; playback verification pending)

This fork targets Media3 **1.8.0** and Android **5.0+ (API 21)**. Its Maven coordinates use `io.github.178meorg`
and version `1.8.0-0.16.0-vc1.5`. See [VC-1 verification](media3ext/VC1_VERIFICATION.md)
for initialization requirements and outstanding device tests.

Release and Debug AARs include all four ABIs: `x86`, `x86_64`, `armeabi-v7a`, and
`arm64-v8a`. Release support for Intel ABIs is restored in `1.8.0-0.16.0-vc1.5`;
previously published versions keep their original ABI contents.

## Setup
Kotlin DSL:

```kotlin
dependencies {
    implementation("io.github.178meorg:nextlib-media3ext:1.8.0-0.16.0-vc1.5") // To add media3 software decoders and extensions
    implementation("io.github.178meorg:nextlib-mediainfo:1.8.0-0.16.0-vc1.5") // To get media info through ffmpeg
}
```

Groovy DSL:

```gradle
dependencies {
    implementation "io.github.178meorg:nextlib-media3ext:1.8.0-0.16.0-vc1.5" // To add media3 software decoders and extensions
    implementation "io.github.178meorg:nextlib-mediainfo:1.8.0-0.16.0-vc1.5" // To get media info through ffmpeg
}
```

## Basic usage

Use `NextRenderersFactory` with extension renderers enabled to make the bundled FFmpeg decoders
available to Media3. The factory retains `DefaultRenderersFactory`'s default of `OFF`:

```kotlin
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

// OFF: Do not add FFmpeg extension renderers.
// ON: Enable them at normal priority, after platform renderers.
// PREFER: Give FFmpeg extension renderers priority.
val renderersFactory = NextRenderersFactory(applicationContext)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

ExoPlayer.Builder(applicationContext)
    .setRenderersFactory(renderersFactory)
    .build()
```

This controls renderer priority and capability fallback: `ON` allows FFmpeg when platform
renderers cannot support a format. It does not automatically recover from runtime decoder failures;
applications own error recovery.

## Runtime decoder switching

Create a `DecoderManager` alongside `NextRenderersFactory` when the application needs to select
video and audio decoders while keeping the same `ExoPlayer` instance:

```kotlin
val decoderManager = DecoderManager()
val renderersFactory = NextRenderersFactory(applicationContext)
    .setDecoderManager(decoderManager)
val player = ExoPlayer.Builder(applicationContext)
    .setRenderersFactory(renderersFactory)
    .build()

decoderManager.attach(player)
decoderManager.selectVideoDecoder(DecoderMode.HARDWARE)
decoderManager.selectAudioDecoder(DecoderMode.AUTO)

decoderManager.detach()
player.release()
```

Installing a `DecoderManager` enables extension renderers at normal priority (`ON`) and MediaCodec
initialization fallback. Video and audio are selected independently. See
[`media3ext/DECODER_SWITCHING.md`](media3ext/DECODER_SWITCHING.md) for mode behavior, lifecycle, and
application-owned error handling.

## Building from source

Use macOS or Linux (WSL on Windows), JDK 17+, `make`, `curl`, `tar`, and
`pkg-config`, Meson, Ninja, and NASM 2.14+ (`brew install meson ninja nasm` on
macOS; `sudo apt-get install meson ninja-build nasm` on Ubuntu). NASM is used for
both FFmpeg and dav1d's x86 assembly. Install [Android CLI](https://developer.android.com/tools/agents/android-cli/download)
and put `android` on PATH, or set `ANDROID_CLI` to its executable path.
Set `sdk.dir` in `local.properties` or export `ANDROID_HOME` to your Android SDK.

```sh
./gradlew assembleRelease
```

Both modules depend on one `:ffmpegSetup` task, which installs missing NDK/CMake
packages with Android CLI and builds the four supported ABIs before CMake runs.
NDK and CMake versions come from `gradle/libs.versions.toml`. Existing SDK tools
are reused; Android CLI is only needed when a package is missing. Complete any
SDK license prompts during initial installation before running a headless build.

The build pins FFmpeg 9.0.1, mbedTLS 3.6.7 (the 3.6 LTS branch), and dav1d 1.5.4.
mbedTLS is built from its complete release archive and linked statically for
HTTPS/TLS support. The build statically links dav1d into FFmpeg's `libavcodec.so`
for every ABI, with assembly optimizations and both 8-bit and high-bit-depth AV1
support. AV1 uses the `libdav1d` decoder in Media3 and in media-info/thumbnail lookups.

Gradle tracks the setup script, tool versions, and generated output so unchanged
builds skip FFmpeg. To force rebuilding it:

```sh
./gradlew :ffmpegSetup --rerun-tasks
```

For standalone use, export `ANDROID_HOME` and run `bash ffmpeg/setup.sh` (always
rebuilds). Run `python3 ffmpeg/test_setup.py` for setup regression checks without
SDK downloads or native compilation.

To verify AV1 decoding and seeking on a disposable ARM64 Android emulator, build
the library and run the native regression test (requires host FFmpeg with the
`libsvtav1` encoder):

```sh
ANDROID_NDK_HOME=/path/to/sdk/ndk/25.2.9519653 ANDROID_SERIAL=emulator-5554 \
  bash media3ext/src/test/cpp/run_av1_decoder_test.sh
```

This checks decoder discovery by both name and codec ID, 8-bit and 10-bit output,
film-grain decoding, one output frame per sample with no delayed frames at EOS,
and decoding again after a seek/flush. AV1 uses dav1d's low-delay mode to match
Media3's `SimpleDecoder` input/output contract.

See [AV1 verification](media3ext/AV1_VERIFICATION.md) for the Next Player playback
checks and tested device coverage.

See [FFmpeg and mbedTLS upgrade verification](ffmpeg/UPGRADE_VERIFICATION.md) for
the current dependency build, TLS, rotation metadata, and playback checks.

## GitHub Actions and Cloudsmith

The build workflow runs on pushes to `main`, pull requests, and manual dispatch.
It uses JDK 25, installs the pinned Android tools, builds all four native ABIs,
runs the JVM tests, and uploads both release AARs.

The publish workflow runs on `v*` tags, `release/**` branches, or manual dispatch.
`VERSION_NAME` in `gradle.properties` is the publication version; tag and release
branch names must match it (for example `v1.8.0-0.16.0-vc1.5` or
`release/1.8.0-0.16.0-vc1.5`). It builds and tests, uploads AARs, publishes the Maven
publications to Cloudsmith without requiring a GPG key, and creates a
GitHub release containing the AARs after publication succeeds.

Configure these repository Secrets in `178meorg/nextlib`:

- `CLOUDSMITH_USERNAME`: your Cloudsmith username.
- `CLOUDSMITH_API_KEY`: API key with upload access to `178meorg/nextlib`.

The publishing endpoint is `https://maven.cloudsmith.io/178meorg/nextlib/`.
To publish locally with credentials supplied through Gradle properties or
`ORG_GRADLE_PROJECT_CloudsmithUsername` / `ORG_GRADLE_PROJECT_CloudsmithPassword`:

```sh
./gradlew publishAllPublicationsToCloudsmithRepository -PreleaseSigningEnabled=false
```

After publication, consumers can use this public Maven repository without credentials.
Confirm the Cloudsmith repository is public before distributing the dependency:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://dl.cloudsmith.io/public/178meorg/nextlib/maven/")
            content { includeGroup("io.github.178meorg") }
        }
    }
}
```

Maven Central remains an optional publishing target. It requires Central credentials
and GPG signing, and is not invoked by the Cloudsmith publishing workflow.
