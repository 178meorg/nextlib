import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.mavenPublish)
}

android {
    namespace = "io.github.anilbeesetti.nextlib.media3ext"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {

        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }

        ndk {
            abiFilters += listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory("src/test/cpp/fixtures")
        if (providers.gradleProperty("nextlibRealMediaProbe").orNull == "true") {
            variant.androidTest?.sources?.assets?.addStaticSourceDirectory(
                rootProject.layout.buildDirectory.dir("realmedia/android-assets").get().asFile.path)
        }
    }
}

// The feasibility probe is opt-in and is never packaged in the published AAR.
if (providers.gradleProperty("nextlibRealMediaProbe").orNull == "true") {
    val probeSourceSet = (android.sourceSets as NamedDomainObjectContainer<com.android.build.api.dsl.AndroidSourceSet>)
        .getByName("androidTest")
    probeSourceSet.java.srcDir("src/realmediaProbe/java")
    probeSourceSet.manifest.srcFile("src/realmediaProbe/AndroidManifest.xml")
    probeSourceSet.jniLibs.srcDir(
        rootProject.layout.buildDirectory.dir("realmedia/android-jni").get().asFile.path)
}

tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(":ffmpegSetup")
    }
}

dependencies {
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.google.errorprone.annotations)
    implementation(libs.androidx.annotation)
    compileOnly(libs.checker.qual)
    compileOnly(libs.kotlin.annotations.jvm)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
