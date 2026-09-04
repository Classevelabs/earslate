import java.util.Base64
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
// earslate upload key is earslate-release.keystore (alias earslate, SHA-256
// pinned in verifyBundleIdentity); see _keystore-backups/README.txt. Signing the
// AAB with the brand key builds cleanly and is then refused at upload, which is
// exactly what blocked earslate on Play.
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
 *
 * Base64, and not for secrecy — the certificate DN is readable in any signed
 * artifact, so this hides nothing from anyone holding an APK. It keeps the
 * plaintext out of the repository, which is a different problem: a scrubber
 * that lists its needles in cleartext is a public index of exactly what the
 * project removes, findable by searching this host for any one of them. The
 * gate is unchanged; only its source form is.
 */
fun identityNeedle(encoded: String): String =
    String(Base64.getDecoder().decode(encoded))

val brandCertificateDn = identityNeedle("Q049RWFyc2xhdGUsIE89Q2xhc3NFdmUsIEM9SU4=")

/**
 * Strings that are never legitimate in a shipped artifact. Deliberately NOT
 * bare city/state words — see [verifyReleaseIdentity]'s KDoc.
 */
val forbiddenIdentityStrings = listOf(
    "UHJpdmF0ZSBMaW1pdGVk",
    "UHZ0IEx0ZA==",
    "UHZ0LiBMdGQ=",
    "QmFybmFsYQ==",
    "QmVuZ2FsdXJ1",
).map(::identityNeedle)

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
    description = "Fails unless the release AAB is signed with the registered Play upload certificate."

    val bundleDir = layout.buildDirectory.dir("outputs/bundle/release")
    // The Play upload certificate for com.classeve.earslate — earslate-release.keystore,
    // alias earslate. Unlike the direct-download APK, its DN carries the legal
    // entity ON PURPOSE: Play App Signing strips it and re-signs before any
    // device sees the app, so it is never a user-facing leak, and Play 403s any
    // OTHER key. So the bundle is pinned to this certificate by SHA-256 (a
    // wrong-key bundle fails here instead of at upload), NOT held to the clean
    // brand DN the APK gate requires. See _keystore-backups/README.txt.
    val uploadCertSha256 = "06DC3708937740310F93D9EF6F400DE16D2619C95E76D0EA50B737B495F3F544"
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
            // An AAB is a JAR, so it carries the v1 signature keytool reads.
            val process = ProcessBuilder(
                keytool.absolutePath, "-printcert", "-jarfile", aab.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) {
                throw GradleException("verifyBundleIdentity: keytool failed on ${aab.name}:\n$output")
            }
            val fingerprints = output.lineSequence()
                .filter { it.contains("SHA256:") }
                .map { it.substringAfter("SHA256:").trim().replace(":", "").uppercase() }
                .toSet()
            if (fingerprints.isEmpty()) {
                throw GradleException(
                    "verifyBundleIdentity: ${aab.name} has no readable signer. An unsigned " +
                        "bundle cannot be uploaded, and an unreadable one cannot be checked:\n$output",
                )
            }
            if (fingerprints != setOf(uploadCertSha256)) {
                throw GradleException(
                    "verifyBundleIdentity: ${aab.name} is not signed with the registered Play " +
                        "upload certificate.\n" +
                        "  expected: $uploadCertSha256\n" +
                        "  actual:   ${fingerprints.sorted().joinToString()}\n" +
                        "Set PLAY_UPLOAD_STORE_FILE/PASSWORD/KEY_ALIAS/KEY_PASSWORD in local.properties. " +
                        "A bundle signed with the brand key builds cleanly and is then refused at upload " +
                        "with a 403, which is how the earslate Play channel was blocked.",
                )
            }
            logger.lifecycle("verifyBundleIdentity: ${aab.name} - signed with the registered Play upload certificate")
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
