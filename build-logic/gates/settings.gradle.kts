// The ClassEve release gates, as an included build. One source, vendored into
// every Android app under build-logic/gates by tools/sync-gates.mjs and held
// byte-identical by tools/drift-check.mjs.
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "classeve-gates"
