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
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Stream WebRTC builds are published to Maven Central, but we include
        // their repo as a fallback for snapshot/edge releases.
        maven { url = uri("https://repo.stream.io/artifactory/maven-public/") }
    }
}

rootProject.name = "TorentChat"
include(":app")
