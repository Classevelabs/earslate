import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.classeve.release-gates")
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
val brandStoreFile = localProperties.getProperty("EARSLATE_STORE_FILE")?.takeIf { it.isNotBlank() }
val brandStorePassword = localProperties.getProperty("EARSLATE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val brandKeyAlias = localProperties.getProperty("EARSLATE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val brandKeyPassword = localProperties.getProperty("EARSLATE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

// An AAB and an APK go to different places and must carry DIFFERENT certificates.
// The APK is the direct download, signed with the brand key whose DN carries no
// company entity. The AAB goes to Google Play, which pins the UPLOAD certificate
// registered when the app was created and rejects anything else with a 403; Play
// App Signing re-signs for users, so that upload certificate — and the legal
// entity in its DN — never reaches a device and is not a user-facing leak. The
// upload certificate's SHA-256 is pinned in classeveGates below and checked by
// verifyBundleIdentity. Signing the AAB with the brand key builds cleanly and is
// then refused at upload, which is exactly what blocked earslate on Play.
val uploadStoreFile = localProperties.getProperty("PLAY_UPLOAD_STORE_FILE")?.takeIf { it.isNotBlank() }
val uploadStorePassword = localProperties.getProperty("PLAY_UPLOAD_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val uploadKeyAlias = localProperties.getProperty("PLAY_UPLOAD_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val uploadKeyPassword = localProperties.getProperty("PLAY_UPLOAD_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasUploadKeystore = uploadStoreFile?.let { file(it).exists() } == true &&
    uploadStorePassword != null && uploadKeyAlias != null && uploadKeyPassword != null

val bundleRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.substringAfterLast(':').startsWith("bundle", ignoreCase = true)
}
val signWithUploadKey = bundleRequested && hasUploadKeystore

// Downstream signingConfigs read these: the brand key for an APK, the Play
// upload key for a bundle.
val releaseStoreFile = if (signWithUploadKey) uploadStoreFile else brandStoreFile
val releaseStorePassword = if (signWithUploadKey) uploadStorePassword else brandStorePassword
val releaseKeyAlias = if (signWithUploadKey) uploadKeyAlias else brandKeyAlias
val releaseKeyPassword = if (signWithUploadKey) uploadKeyPassword else brandKeyPassword
val releaseSigningProperties = linkedMapOf(
    "store file" to releaseStoreFile,
    "store password" to releaseStorePassword,
    "key alias" to releaseKeyAlias,
    "key password" to releaseKeyPassword,
)
val missingReleaseSigningProperties = releaseSigningProperties
    .filterValues { it == null }
    .keys
val hasReleaseKeystore = missingReleaseSigningProperties.isEmpty()

// The identity needles are release configuration, not source: this repository
// is public, and a list here would publish the strings it keeps out of the APK.
val identityNeedles = localProperties.getProperty("EARSLATE_IDENTITY_NEEDLES")
    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

if (bundleRequested && !hasUploadKeystore) {
    println(
        "WARNING: PLAY_UPLOAD_* is not configured, so this bundle would be signed with the brand key " +
            "and Play will refuse it (it expects the registered upload certificate). Set " +
            "PLAY_UPLOAD_STORE_FILE/PASSWORD/KEY_ALIAS/KEY_PASSWORD in local.properties.",
    )
}

android {
    namespace = "com.classeve.earslate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.classeve.earslate"
        minSdk = 29
        targetSdk = 36
        // 0.4.4 moved the APK to the brand certificate, so an install signed by
        // the older key cannot take a later version as an update.
        versionCode = 28
        versionName = "0.5.3"

        vectorDrawables.useSupportLibrary = true

        // The audio teardown paths cannot be covered by JVM unit tests:
        // AudioTrack and AudioRecord are stubs off-device, so the two races
        // fixed in 0.4.4 were reasoning-verified only. These run on a device or
        // emulator and exercise the real framework objects.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // android.util.Log throws "not mocked" by default, so any unit test
            // that walks a code path containing a log line fails for a reason
            // that has nothing to do with the behaviour under test. That made the
            // parser's error branches — precisely the ones that must never throw
            // — untestable off-device. Stubs return defaults instead.
            isReturnDefaultValues = true
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
        if (identityNeedles.isEmpty()) {
            throw GradleException(
                "Release identity is not configured: set EARSLATE_IDENTITY_NEEDLES in local.properties. " +
                    "Debug builds remain available.",
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

// Release gates come from the shared plugin in build-logic/gates, vendored from
// SYSTEM/gradle-gates and held byte-identical by the drift check. Only what is
// specific to THIS app lives here: the brand DN the direct-download APK must
// carry, the Play upload certificate the AAB is pinned to, and the identity
// needles from local.properties.
classeveGates {
    apkSignerDn.set("CN=Earslate, O=ClassEve, C=IN")
    bundleSignerSha256.set("06DC3708937740310F93D9EF6F400DE16D2619C95E76D0EA50B737B495F3F544")
    forbiddenNeedlesBase64.set(identityNeedles)
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

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
