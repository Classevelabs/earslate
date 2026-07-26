import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
}

// earslate has no backend. There is no service URL to configure, no broker to
// point at, and no API key of ours anywhere in the build — the user supplies
// their own at runtime and it is sealed by the device keystore.

// Release keystore — four coordinates come from local.properties so the
// keystore binary itself stays off-repo. Debug builds remain available without
// these values, but every release build is gated by verifyReleaseSigning below.
// Verify the production keystore has been used with:
//   $ jarsigner -verify -verbose app/build/outputs/apk/release/app-release.apk
val releaseStoreFile = localProperties.getProperty("EARSLATE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = localProperties.getProperty("EARSLATE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = localProperties.getProperty("EARSLATE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = localProperties.getProperty("EARSLATE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseSigningProperties = linkedMapOf(
    "EARSLATE_STORE_FILE" to releaseStoreFile,
    "EARSLATE_STORE_PASSWORD" to releaseStorePassword,
    "EARSLATE_KEY_ALIAS" to releaseKeyAlias,
    "EARSLATE_KEY_PASSWORD" to releaseKeyPassword,
)
val missingReleaseSigningProperties = releaseSigningProperties
    .filterValues { it == null }
    .keys
val hasReleaseKeystore = missingReleaseSigningProperties.isEmpty()

android {
    namespace = "com.classeve.earslate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.classeve.earslate"
        minSdk = 29
        targetSdk = 36
        versionCode = 17
        versionName = "0.4.2"

        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        disable += "GradleDependency"
        disable += "ObsoleteSdkInt"
    }
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails unless production release signing is fully configured."
    doLast {
        if (missingReleaseSigningProperties.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing: " +
                    missingReleaseSigningProperties.joinToString() +
                    ". Debug builds remain available, but release builds fail closed.",
            )
        }
        if (!file(requireNotNull(releaseStoreFile)).isFile) {
            throw GradleException(
                "Release signing is not configured: EARSLATE_STORE_FILE does not point " +
                    "to an existing keystore. Debug builds remain available.",
            )
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Used by the Gemini Live WebSocket client (OkHttpLiveSocketClient).
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android stubs org.json in the unit-test android.jar, so every call throws
    // "not mocked". The real implementation lets us assert the exact JSON we
    // send to a provider without needing a device.
    testImplementation("org.json:json:20240303")
}
