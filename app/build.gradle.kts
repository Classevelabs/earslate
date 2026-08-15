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
        // 0.4.5 — a session's configuration is now stated once rather than
        // twice (captions-off minted a token that contradicted its own setup
        // frame), a cold start no longer ignores the user's languages, a socket
        // death no longer tears down the foreground service it needs to
        // reconnect, and diagnosed failures are no longer laundered into
        // "check your network". See the log between v0.4.4 and here.
        //
        // 0.4.4 — brand signing certificate (the 0.4.3 cert leaked the legal
        // entity, city and state into every APK), BLUETOOTH_CONNECT removed,
        // two audio-teardown races fixed. That certificate change is why an
        // install signed by the old key cannot take these as an update.
        versionCode = 22
        versionName = "0.4.7"

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

    kotlinOptions {
        jvmTarget = "17"
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

/**
 * The one definition of what a clean artifact looks like.
 *
 * Both the APK gate and the bundle gate read these. They were nearly written
 * out twice, which is the same mistake as any other duplicated contract: the
 * copies drift, each has its own passing check, and neither notices.
 */
val brandCertificateDn = "CN=Earslate, O=ClassEve, C=IN"

/**
 * Strings that are never legitimate in a shipped artifact. Deliberately NOT
 * bare city/state words — see [verifyReleaseIdentity]'s KDoc.
 */
val forbiddenIdentityStrings = listOf(
    "REDACTED",
    "Pvt Ltd",
    "Pvt. Ltd",
    "REDACTED",
    "Bengaluru",
)

/**
 * Fails the build if a release APK carries the legal entity, a city, or a state.
 *
 * This exists because the check did not, and the omission shipped. Up to 0.4.3
 * the release keystore's DN carried OU, O, L and ST attributes well beyond the
 * brand name, so every published APK — including the one served from
 * classeve.com — had them in its bytes, against INTERNAL-RULES §2. Those
 * attribute values are written down exactly once, in
 * [forbiddenIdentityStrings]; restating them here would only add a second copy
 * to scrub.
 *
 * It survived because the obvious checks all report CLEAN on a dirty APK: the
 * DN is DER-encoded inside the v2 signing block, and these builds carry no v1
 * signature, so `strings` finds nothing and `keytool -printcert -jarfile`
 * has no JAR signature to read.
 *
 * Two assertions, because either alone has a hole:
 *  1. The signer DN must equal [brandCertificateDn] EXACTLY. Checking the DN
 *     rather than grepping for place names is what makes this precise: a bare
 *     state word is also the prefix of a language name, and the day that
 *     language is added to SupportedLanguages a substring scan would fail an
 *     innocent build.
 *  2. A raw-byte scan for entity strings that can never be legitimate,
 *     catching a leak that arrives through some path other than the
 *     certificate.
 *
 * Fails closed: if apksigner cannot be found or run, that is a failure, not a
 * skip. A gate that quietly does nothing is worse than no gate, because it
 * reads as proof.
 */
val verifyReleaseIdentity by tasks.registering {
    group = "verification"
    description = "Fails if the release APK leaks the legal entity, a city, or a state."

    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    val sdkDir = localProperties.getProperty("sdk.dir")
    val allowedDn = brandCertificateDn
    val forbiddenSubstrings = forbiddenIdentityStrings

    doLast {
        val apks = apkDir.get().asFile.listFiles { f: File -> f.name.endsWith(".apk") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (apks.isEmpty()) {
            throw GradleException("verifyReleaseIdentity: no release APK found in ${apkDir.get().asFile}")
        }

        val sdk = sdkDir?.let(::File)
            ?: throw GradleException("verifyReleaseIdentity: sdk.dir is not set in local.properties")
        val apksignerJar = File(sdk, "build-tools").listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.reversed()
            ?.map { File(it, "lib/apksigner.jar") }
            ?.firstOrNull { it.isFile }
            ?: throw GradleException(
                "verifyReleaseIdentity: apksigner.jar not found under ${File(sdk, "build-tools")}. " +
                    "The identity gate fails closed rather than skipping.",
            )

        for (apk in apks) {
            // 1. Exact signer DN.
            val process = ProcessBuilder(
                "java", "-jar", apksignerJar.absolutePath,
                "verify", "--print-certs", apk.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) {
                throw GradleException("verifyReleaseIdentity: apksigner failed on ${apk.name}:\n$output")
            }
            val dnLine = output.lineSequence()
                .firstOrNull { it.contains("certificate DN:") }
                ?: throw GradleException(
                    "verifyReleaseIdentity: apksigner printed no certificate DN for ${apk.name}:\n$output",
                )
            val dn = dnLine.substringAfter("certificate DN:").trim()
            if (dn != allowedDn) {
                throw GradleException(
                    "verifyReleaseIdentity: ${apk.name} is signed with a non-brand certificate.\n" +
                        "  expected: $allowedDn\n" +
                        "  actual:   $dn\n" +
                        "INTERNAL-RULES §2: no OU, no O beyond 'ClassEve', no L, no ST.",
                )
            }

            // 2. Raw bytes, because a certificate is not the only way to leak.
            val bytes = apk.readBytes()
            val haystack = String(bytes, Charsets.ISO_8859_1)
            val hits = forbiddenSubstrings.filter { haystack.contains(it) }
            if (hits.isNotEmpty()) {
                throw GradleException(
                    "verifyReleaseIdentity: ${apk.name} contains forbidden identity strings: " +
                        hits.joinToString() + " (INTERNAL-RULES §2)",
                )
            }
            // ASCII only: CI log encodings mangle anything else.
            logger.lifecycle("verifyReleaseIdentity: ${apk.name} - DN clean, raw bytes clean")
        }
    }
}

/**
 * The same gate, on the artifact Play actually receives.
 *
 * [verifyReleaseIdentity] reads `outputs/apk/release` and is wired only to
 * `assembleRelease`. Play is given an **AAB**, produced by `bundleRelease`, and
 * those are separate Gradle invocations — so the gate written *because* the
 * entity leak shipped never ran on the thing that ships. The APK it does check
 * is the one served from classeve.com; the store build had no check at all.
 *
 * `apksigner` cannot read an AAB, but it does not need to: an AAB is a JAR, so
 * it carries a v1 signature that `keytool -printcert -jarfile` can read — the
 * tool the APK KDoc dismisses, for the opposite reason. There it fails because
 * these APKs have no v1 signature; here v1 is all there is.
 *
 * Fails closed, like its sibling: no bundle, no keytool, or no readable signer
 * is a failure and not a skip.
 */
val verifyBundleIdentity by tasks.registering {
    group = "verification"
    description = "Fails if the release AAB leaks the legal entity, a city, or a state."

    val bundleDir = layout.buildDirectory.dir("outputs/bundle/release")
    val allowedDn = brandCertificateDn
    val forbiddenSubstrings = forbiddenIdentityStrings
    val javaHome = System.getProperty("java.home")

    doLast {
        val bundles = bundleDir.get().asFile.listFiles { f: File -> f.name.endsWith(".aab") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (bundles.isEmpty()) {
            throw GradleException("verifyBundleIdentity: no release AAB found in ${bundleDir.get().asFile}")
        }

        val keytool = File(javaHome, if (File(javaHome, "bin/keytool.exe").isFile) "bin/keytool.exe" else "bin/keytool")
        if (!keytool.isFile) {
            throw GradleException(
                "verifyBundleIdentity: keytool not found at $keytool. " +
                    "The identity gate fails closed rather than skipping.",
            )
        }

        for (aab in bundles) {
            // 1. Exact signer DN, read from the JAR signature.
            val process = ProcessBuilder(
                keytool.absolutePath, "-printcert", "-jarfile", aab.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) {
                throw GradleException("verifyBundleIdentity: keytool failed on ${aab.name}:\n$output")
            }
            val owners = output.lineSequence()
                .filter { it.trimStart().startsWith("Owner:") }
                .map { it.substringAfter("Owner:").trim() }
                .toList()
            if (owners.isEmpty()) {
                throw GradleException(
                    "verifyBundleIdentity: ${aab.name} has no readable signer. An unsigned " +
                        "bundle cannot be uploaded, and an unreadable one cannot be checked:\n$output",
                )
            }
            // keytool and apksigner space a DN differently; compare on content,
            // not on formatting, so this fails on identity and never on layout.
            fun normalise(dn: String) = dn.split(",").joinToString(", ") { it.trim() }
            for (owner in owners) {
                if (normalise(owner) != normalise(allowedDn)) {
                    throw GradleException(
                        "verifyBundleIdentity: ${aab.name} is signed with a non-brand certificate.\n" +
                            "  expected: $allowedDn\n" +
                            "  actual:   $owner\n" +
                            "INTERNAL-RULES 2: no OU, no O beyond 'ClassEve', no L, no ST.",
                    )
                }
            }

            // 2. Raw bytes, because a certificate is not the only way to leak.
            val haystack = String(aab.readBytes(), Charsets.ISO_8859_1)
            val hits = forbiddenSubstrings.filter { haystack.contains(it) }
            if (hits.isNotEmpty()) {
                throw GradleException(
                    "verifyBundleIdentity: ${aab.name} contains forbidden identity strings: " +
                        hits.joinToString() + " (INTERNAL-RULES 2)",
                )
            }
            logger.lifecycle("verifyBundleIdentity: ${aab.name} - DN clean, raw bytes clean")
        }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(verifyReleaseIdentity)
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    finalizedBy(verifyBundleIdentity)
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
