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

val geminiApiKey = (localProperties.getProperty("GEMINI_API_KEY") ?: "").escapeForBuildConfig()
val geminiLiveModel = (localProperties.getProperty("GEMINI_LIVE_MODEL") ?: "gemini-3.5-live-translate-preview").escapeForBuildConfig()
val workerUrl = (localProperties.getProperty("WORKER_URL") ?: "https://api.classeve.com").escapeForBuildConfig()

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

        // GEMINI_LIVE_MODEL + WORKER_URL are non-secret and ship in every variant.
        buildConfigField("String", "GEMINI_LIVE_MODEL", "\"$geminiLiveModel\"")
        buildConfigField("String", "WORKER_URL", "\"$workerUrl\"")
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
            // Debug-only: devs can set GEMINI_API_KEY in local.properties for
            // direct-to-Gemini iteration without having to spin up the Worker.
            // The constant is blank by default so even debug builds fail closed
            // when the key isn't set.
            buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Release builds must NEVER carry the long-lived Gemini key. The
            // constant is defined so code that references BuildConfig.GEMINI_API_KEY
            // still compiles, but the value is always empty — the session flow
            // routes through RemoteBootstrapRepository → /v1/earslate/bootstrap →
            // single-use ephemeral token instead.
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
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

    // Session storage: EncryptedSharedPreferences-backed AuthStore.
    // Direct coordinates — adding these to the version catalog collided
    // with the existing androidx.* group accessors on Gradle 8.5.
    implementation("androidx.security:security-crypto:1.1.0")

    // Background token refresh via WorkManager (future TokenRefreshWorker).
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
