# NextLib

[![Build nextlib](https://github.com/anilbeesetti/nextlib/actions/workflows/build.yaml/badge.svg)](https://github.com/anilbeesetti/nextlib/actions/workflows/build.yaml) [![Maven Central](https://img.shields.io/maven-central/v/io.github.anilbeesetti/nextlib-media3ext.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.anilbeesetti/nextlib-media3ext)

NextLib is a library for adding ffmpeg codecs to [Media3](https://github.com/androidx/media).

## Currently supported decoders
- **Audio**: Vorbis, Opus, Flac, Alac, pcm_mulaw, pcm_alaw, MP3, Amrnb, Amrwb, AAC, AC3, EAC3, dca, mlp, truehd
- **Video**: H.264, HEVC, VP8, VP9

## Setup
Kotlin DSL:

```kotlin
dependencies {
    implementation("io.github.anilbeesetti:nextlib-media3ext:INSERT_VERSION_HERE") // To add media3 software decoders and extensions
    implementation("io.github.anilbeesetti:nextlib-mediainfo:INSERT_VERSION_HERE") // To get media info through ffmpeg
}
```

Groovy DSL:

```gradle
dependencies {
    implementation "io.github.anilbeesetti:nextlib-media3ext:INSERT_VERSION_HERE" // To add media3 software decoders and extensions
    implementation "io.github.anilbeesetti:nextlib-mediainfo:INSERT_VERSION_HERE" // To get media info through ffmpeg
}
```

## Usage

To use Ffmpeg decoders in your app, Add `NextRenderersFactory` (is one to one compatible with DefaultRenderersFactory) to `ExoPlayer`
```kotlin
val renderersFactory = NextRenderersFactory(applicationContext) 

ExoPlayer.Builder(applicationContext)
    .setRenderersFactory(renderersFactory)
    .build()
```

## Experimental RM/RMVB support

This fork includes an opt-in FFmpeg-backed RealMedia extractor. See [integration and testing notes](docs/realmedia.md).

## Example app

See [example/README.md](example/README.md) for the standalone SMB browser/player with server profiles,
shared-folder browsing, decoder selection, and playback controls.
Build with `./gradlew :example:assembleDebug`; no Maven publication is required.
