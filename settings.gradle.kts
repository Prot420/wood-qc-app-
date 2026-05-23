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
        // JitPack fallback for community-maintained Android libraries
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "WoodQCApp"
include(":app")
