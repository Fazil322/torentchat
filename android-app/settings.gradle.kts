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
        // Signal Protocol library (libsignal-android) is published to
        // Signal's public Cloudsmith repository, not Maven Central.
        maven { url = uri("https://dl.cloudsmith.io/public/communication/libsignal/maven/") }
    }
}

rootProject.name = "TorentChat"
include(":app")
