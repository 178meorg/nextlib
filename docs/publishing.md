# Building and publishing

The same source tree builds against two Media3 versions:

| Build option | Media3 | Minimum Android API |
| --- | --- | --- |
| Default, or `-Pmedia3Version=1.8.0` | 1.8.0 | 21 |
| `-Pmedia3Version=1.10.1` | 1.10.1 | 23 |

The version catalog defines the default. The build parameter overrides Media3 dependencies
and the minimum API for both `media3ext` and the example app. Other Media3 versions are
rejected until their API requirements and compatibility have been verified.
FFmpeg remains compiled for API 21 and is shared by both builds.

## Makefile shortcuts

```bash
make build                          # Compile both versions sequentially
make publish                        # Publish both versions to the configured repository
make publish VERSION=0.9.0-rmvb.3    # Override the shared extension version
make publish-local                  # Install both versions into local Maven
make publish-legacy                 # Publish only Media3 1.8.0
make publish-modern                 # Publish only Media3 1.10.1
```

The default extension version comes from `gradle.properties`. Use `make help` for available
targets. Targets run sequentially even with `make -j`, because build outputs are shared.
Additional Gradle options can be passed with `GRADLE_ARGS`, for example
`make build GRADLE_ARGS="--offline --console=plain"`.

## Build locally

Use JDK 17 and install the Android SDK, NDK, and CMake versions specified in
`gradle/libs.versions.toml`. The first build compiles FFmpeg for all supported ABIs.

```bash
./gradlew :media3ext:assembleRelease :example:assembleDebug -Pmedia3Version=1.8.0
./gradlew :media3ext:assembleRelease :example:assembleDebug -Pmedia3Version=1.10.1
```

Run these commands sequentially: both builds share the same output directories and native
cache. The AAR is written to `media3ext/build/outputs/aar/media3ext-release.aar`;
copy it before switching versions if you need to retain both files.

## Maven versions

Publication versions use `<Media3 version>-<extension version>`. The shared extension version
is configured by `nextlibVersion` in `gradle.properties` and can be overridden on the command line.
For example, `-PnextlibVersion=0.9.0-rmvb.2` produces `1.8.0-0.9.0-rmvb.2` or
`1.10.1-0.9.0-rmvb.2`, according to the selected Media3 version.

To install both builds in the local Maven repository for integration:

```bash
./gradlew :media3ext:publishToMavenLocal -Pmedia3Version=1.8.0
./gradlew :media3ext:publishToMavenLocal -Pmedia3Version=1.10.1
```

Consumer projects need `mavenLocal()` for local integration. Use only one NextLib artifact
per app and keep every Media3 module on that artifact's matching Media3 version.

To inspect publication metadata before publishing:

```bash
./gradlew :media3ext:generatePomFileForMavenPublication -Pmedia3Version=1.10.1
```

The POM is written to `media3ext/build/publications/maven/pom-default.xml` and is overwritten
by the next build. Verify its publication version and Media3 dependency before uploading.
An explicit `-PreleaseVersion=<full version>` is also supported; its prefix must match the
selected Media3 version. Configure remote repository credentials locally, and publish the
AAR together with its POM and Gradle module metadata using a new version for each release.

RM/RMVB playback also requires `NextExtractorsFactory`; see [realmedia.md](realmedia.md).
