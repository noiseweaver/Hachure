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
        // Onyx pen SDK (used from Milestone 2). See README "Onyx SDK dependencies".
        maven("https://repo.boox.com/repository/maven-public/") {
            content { includeGroupByRegex("com\\.onyx.*") }
        }
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github.*") }
        }
    }
}

rootProject.name = "hachure"
include(":core")
// Set -Pcore.only=true to build and test :core without the Android SDK.
if (providers.gradleProperty("core.only").orNull != "true") {
    include(":app")
}
