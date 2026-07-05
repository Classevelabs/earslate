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

fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

// earslate is bring-your-own-key: the Gemini key itself is entered by the
// user at runtime and stored on-device (see GeminiKeyStore) — it never comes
// from BuildConfig or local.properties. Only the (non-secret) model id ships
// as a build-time default.
val geminiLiveModel = (localProperties.getProperty("GEMINI_LIVE_MODEL") ?: "gemini-3.5-live-translate-preview").escapeForBuildConfig()

// Release keystore — four coordinates come from local.properties so the
// keystore binary itself stays off-repo. If any coordinate is missing the
// release build falls back to the debug signing config (so the APK is at
// least installable) and the message below is printed at configure time.
// Verify the production keystore has been used with:
//   $ jarsigner -verify -verbose app/build/outputs/apk/release/app-release.apk
val releaseStoreFile = localProperties.getProperty("EARSLATE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = localProperties.getProperty("EARSLATE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = localProperties.getProperty("EARSLATE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = localProperties.getProperty("EARSLATE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseKeystore = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null
if (!hasReleaseKeystore) {
    println(
        "WARNING: EARSLATE_STORE_FILE/PASSWORD/KEY_ALIAS/KEY_PASSWORD not set " +
            "in local.properties — release builds will be signed with the debug key. " +
            "Generate a keystore and set the four properties before producing a Play upload.",
    )
}

android {
    namespace = "com.classeve.earslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.classeve.earslate"
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "0.1.10"

        // Non-secret, ships in every variant. The actual Gemini API key is
        // supplied by the user at runtime (GeminiKeyStore) — never here.
        buildConfigField("String", "GEMINI_LIVE_MODEL", "\"$geminiLiveModel\"")
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
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Wire the production keystore if it's present; otherwise fall back
            // to the debug key so engineers can still produce a runnable
            // release-flavoured APK locally for testing minify/shrink. Play
            // Console will reject debug-signed uploads — use the warning at
            // configure time to know which signing config is in effect.
            // (AGP 8.5 implicitly creates the "debug" signingConfig — relying on it.)
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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

    // On-device encrypted storage for the user's own Gemini API key
    // (GeminiKeyStore / SecurePrefs). Direct coordinates — adding these to
    // the version catalog collided with the existing androidx.* group
    // accessors on Gradle 8.5.
    implementation("androidx.security:security-crypto:1.1.0")

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Used by the Gemini Live WebSocket client (OkHttpLiveSocketClient).
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
