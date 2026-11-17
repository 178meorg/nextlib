import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.publish.PublishingExtension

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.mavenPublish) apply false
}

val media3Version = libs.versions.androidxMedia3.get()
val nextlibVersion = providers.gradleProperty("nextlibVersion").get()
val releaseVersion = providers.gradleProperty("releaseVersion")
    .getOrElse("$media3Version-$nextlibVersion")
require(releaseVersion.matches(Regex("[0-9][0-9A-Za-z._-]*"))) {
    "Invalid releaseVersion '$releaseVersion'"
}
require(releaseVersion.startsWith("$media3Version-") && releaseVersion.length > media3Version.length + 1) {
    "releaseVersion '$releaseVersion' must start with '$media3Version-' and include an extension version"
}

subprojects {
    plugins.withId(rootProject.libs.plugins.mavenPublish.get().pluginId) {
        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            coordinates(
                groupId = "cloud.lazycat",
                artifactId = property("POM_ARTIFACT_ID") as String,
                version = releaseVersion
            )

            pom {
                name = property("POM_NAME") as String
                description = property("POM_DESCRIPTION") as String
                url = "https://github.com/anilbeesetti/nextlib"

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
                    url = "https://github.com/anilbeesetti/nextlib"
                }
            }
        }
        configure<PublishingExtension> {
            repositories {
                providers.gradleProperty("companyRepositoryUrl").orNull?.let { repositoryUrl ->
                    maven {
                        name = "company"
                        url = uri(repositoryUrl)
                        credentials(PasswordCredentials::class)
                    }
                }
            }
        }
    }
}
