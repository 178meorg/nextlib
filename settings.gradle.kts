pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    versionCatalogs {
        create("libs") {
            providers.gradleProperty("media3Version").orNull?.let { media3Version ->
                val minSdk = when (media3Version) {
                    "1.8.0" -> "21"
                    "1.10.1" -> "23"
                    else -> error("Unsupported media3Version '$media3Version'; supported versions: 1.8.0, 1.10.1")
                }
                version("androidxMedia3", media3Version)
                version("media3MinSdk", minSdk)
            }
        }
    }
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nextlib"
include(":media3ext")
include(":mediainfo")

include(":example")
