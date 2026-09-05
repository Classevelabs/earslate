plugins {
    `kotlin-dsl`
}

group = "com.classeve"
version = "1"

gradlePlugin {
    plugins {
        create("releaseGates") {
            id = "com.classeve.release-gates"
            implementationClass = "com.classeve.gates.ReleaseGatesPlugin"
        }
    }
}

dependencies {
    // Compile against the AGP DSL only. The consuming app supplies the real
    // AGP at runtime; the plugin touches nothing newer than manifest
    // placeholders, which every AGP 8.x exposes identically.
    compileOnly("com.android.tools.build:gradle-api:8.13.2")
}
