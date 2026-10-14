plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.mavenPublish) apply false
}

// One shared producer for both modules, including parallel Gradle builds.
val ffmpegSetup = tasks.register<Exec>("ffmpegSetup") {
    group = "build"
    description = "Build FFmpeg and its dependencies for all Android ABIs"
    workingDir = file("ffmpeg")
    val sdkDirectory = providers.fileContents(layout.projectDirectory.file("local.properties"))
        .asText.orElse("").map { contents ->
            java.util.Properties().apply { load(contents.reader()) }.getProperty("sdk.dir", "")
        }.get().ifBlank {
            providers.environmentVariable("ANDROID_HOME")
                .orElse(providers.environmentVariable("ANDROID_SDK_ROOT")).getOrElse("")
        }
    environment("ANDROID_HOME", sdkDirectory)
    environment("ANDROID_NDK_VERSION", libs.versions.ndk.get())
    environment("ANDROID_CMAKE_VERSION", libs.versions.cmake.get())
    inputs.file(file("ffmpeg/setup.sh"))
    inputs.property("ndkVersion", libs.versions.ndk.get())
    inputs.property("cmakeVersion", libs.versions.cmake.get())
    outputs.dir(file("ffmpeg/output"))
    commandLine("bash", "setup.sh")
}

subprojects {
    plugins.withId(rootProject.libs.plugins.mavenPublish.get().pluginId) {
        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            if (providers.gradleProperty("releaseSigningEnabled").orElse("true").get().toBoolean()) {
                signAllPublications()
            }
            coordinates(
                groupId = "io.github.178meorg",
                artifactId = property("POM_ARTIFACT_ID") as String,
                version = providers.gradleProperty("VERSION_NAME").get()
            )

            pom {
                name = property("POM_NAME") as String
                description = property("POM_DESCRIPTION") as String
                url = "https://github.com/178meorg/nextlib"

                licenses {
                    license {
                        name = "GNU General Public License v3.0"
                        url = "https://www.gnu.org/licenses/gpl-3.0.html"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "anilbeesetti"
                        name = "Anil Kumar Beesetti"
                    }
                }
                scm {
                    url = "https://github.com/178meorg/nextlib"
                }
            }
        }
        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "Cloudsmith"
                    url = uri("https://maven.cloudsmith.io/178meorg/nextlib/")
                    credentials(PasswordCredentials::class)
                }
            }
        }
    }
}
