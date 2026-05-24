// ============================================================
// settings.gradle.kts  —  WoodQCApp
// ============================================================
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack: Required for com.github.jeziellago:opencvdroid:4.1.0
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "WoodQCApp"
include(":app")
