pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
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
        // libadb-android, its spake2 pairing dependency and sun-security-android
        // (X.509 cert generation) are published via JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MANC"
include(":app")
