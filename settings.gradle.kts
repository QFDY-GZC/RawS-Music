pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Prefer reachable authoritative repositories for Android/Kotlin dependencies.
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // Keep the mirrors only as a last-resort fallback; a DNS failure must not block
        // dependencies that are already available from Maven Central.
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "RawSMusic"

include(":app")
include(":backdrop")
include(":core:common")
include(":core:ui")
include(":module:player")
include(":module:scanner")
include(":module:data")
include(":lyric:model")
include(":lyric:bridge:provider")
