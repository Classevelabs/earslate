pluginManagement {
    // The ClassEve release gates: one plugin, vendored byte-identical from
    // SYSTEM/gradle-gates by tools/sync-gates.mjs. A missing directory fails
    // configuration, which is the point: a release cannot skip its gates.
    includeBuild("build-logic/gates")
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
    }
}

rootProject.name = "earslate"
include(":app")
