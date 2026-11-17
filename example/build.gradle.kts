plugins { alias(libs.plugins.androidApplication) }
android {
    namespace = "cloud.lazycat.nextlib.example"
    compileSdk = 36
    defaultConfig {
        applicationId = "cloud.lazycat.nextlib.example"
        minSdk = libs.versions.media3MinSdk.get().toInt()
        targetSdk = 35
        versionCode = 3
        versionName = "0.3-smb"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":media3ext"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("org.slf4j:slf4j-nop:1.7.36")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

android.testOptions.unitTests.isIncludeAndroidResources = true
tasks.withType<Test>().configureEach {
    providers.gradleProperty("nextlibSmbTestPort").orNull?.let { systemProperty("nextlib.smb.port", it) }
}
