package com.classeve.gates

import com.android.build.api.dsl.ApplicationExtension
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * The ClassEve release gates. Applied by every Android app; configured once
 * per app through [ReleaseGatesExtension]; registered here exactly once.
 *
 * Until 2026-09-05 each app carried its own 70 to 80 line copy of these
 * checks. The copies had already drifted (needle lists, DN comparison, the
 * bundle signer read three different ways) and one copy had silently
 * disabled itself through a Kotlin-DSL comment trap. A gate that exists four
 * times is four gates that can each be wrong; this is one.
 *
 * Every gate fails closed. A missing tool, artifact or mapping is a failure,
 * not a skip, because a gate that quietly does nothing reads as proof.
 *
 * Tasks:
 *  - requireCleanTree     the release tree must be committed (-PallowDirty=true is the loud escape)
 *  - verifyReleaseIdentity APK signer DN equals the brand DN as a set of RDNs; no identity needle in the bytes
 *  - verifyBundleIdentity  AAB signer certificate SHA-256 equals the Play upload pin; no needle in any entry
 *  - verifyReleaseHygiene  the R8 mapping names no forbidden class (the deprecated edge-to-edge helper)
 *  - verifyArtifactBudget  APK, dex and AAB sizes stay under the ratchet in release-budgets.properties
 * and the commit stamp: manifestPlaceholders["classeveCommit"] = short SHA, "-dirty" when uncommitted.
 */
abstract class ReleaseGatesExtension {
    /** Signer DN the direct-download APK must carry, e.g. "CN=Folio PDF, O=ClassEve, C=IN". */
    abstract val apkSignerDn: Property<String>

    /** SHA-256 of the Play upload certificate the AAB must be signed with, hex, any case. */
    abstract val bundleSignerSha256: Property<String>

    /** Base64 of strings that may never appear in a shipped artifact. Base64 so the list is not an index. */
    abstract val forbiddenNeedlesBase64: ListProperty<String>

    /** Class-name prefixes that must not survive shrinking (read from the release mapping). */
    abstract val forbiddenMappingPrefixes: ListProperty<String>

    /** Properties file with apk.maxBytes, dex.maxBytes, aab.maxBytes; relative to the app project. */
    abstract val budgetsFile: Property<String>
}

class ReleaseGatesPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val gates = project.extensions.create("classeveGates", ReleaseGatesExtension::class.java)
        gates.forbiddenNeedlesBase64.convention(emptyList())
        gates.forbiddenMappingPrefixes.convention(listOf("androidx.activity.EdgeToEdge"))
        gates.budgetsFile.convention("release-budgets.properties")

        val rootDir = project.rootDir
        val commitStamp = commitStamp(project, rootDir)

        project.plugins.withId("com.android.application") {
            val android = project.extensions.getByType(ApplicationExtension::class.java)
            android.defaultConfig.manifestPlaceholders["classeveCommit"] = commitStamp
        }

        val apkDir = project.layout.buildDirectory.dir("outputs/apk/release")
        val aabDir = project.layout.buildDirectory.dir("outputs/bundle/release")
        val mapping = project.layout.buildDirectory.file("outputs/mapping/release/mapping.txt")

        val requireCleanTree = project.tasks.register("requireCleanTree") {
            group = "verification"
            description = "Fails a release built from uncommitted work. -PallowDirty=true is the loud escape."
            val allowDirty = project.providers.gradleProperty("allowDirty").map { it == "true" }.orElse(false)
            doLast {
                val dirty = git(rootDir, "status", "--porcelain").lines().filter { it.isNotBlank() }
                if (dirty.isEmpty()) {
                    logger.lifecycle("requireCleanTree: tree is committed at $commitStamp")
                    return@doLast
                }
                val listing = dirty.take(12).joinToString("\n") { "  $it" } +
                    if (dirty.size > 12) "\n  ... and ${dirty.size - 12} more" else ""
                if (allowDirty.get()) {
                    logger.warn(
                        "requireCleanTree: BUILDING FROM A DIRTY TREE because -PallowDirty=true. " +
                            "The artifact is stamped $commitStamp and matches no commit.\n$listing",
                    )
                    return@doLast
                }
                throw GradleException(
                    "requireCleanTree: ${dirty.size} uncommitted path(s). A release must come from a commit, " +
                        "so it can be rebuilt and so the stamp means something.\n$listing\n" +
                        "Commit or stash first, or pass -PallowDirty=true knowingly.",
                )
            }
        }

        val verifyReleaseIdentity = project.tasks.register("verifyReleaseIdentity") {
            group = "verification"
            description = "Fails unless the release APK is signed with the brand DN and carries no identity needle."
            val sdk = sdkDirectory(rootDir)
            doLast {
                val allowed = gates.apkSignerDn.orNull?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("verifyReleaseIdentity: classeveGates.apkSignerDn is not set.")
                val needles = gates.forbiddenNeedlesBase64.get().map(::decodeNeedle)
                val apks = listArtifacts(apkDir.get().asFile, ".apk", "verifyReleaseIdentity", "APK")
                val apksigner = apksignerJar(sdk)
                for (apk in apks) {
                    val output = run(javaBinary(), "-jar", apksigner.absolutePath, "verify", "--print-certs", apk.absolutePath)
                    val dn = output.lineSequence().firstOrNull { it.contains("certificate DN:") }
                        ?.substringAfter("certificate DN:")?.trim()
                        ?: throw GradleException("verifyReleaseIdentity: apksigner printed no certificate DN for ${apk.name}:\n$output")
                    if (rdns(dn) != rdns(allowed)) {
                        throw GradleException(
                            "verifyReleaseIdentity: ${apk.name} is signed with a non-brand certificate.\n" +
                                "  expected: $allowed\n  actual:   $dn\n" +
                                "INTERNAL-RULES 2: no OU, no O beyond the brand, no L, no ST.",
                        )
                    }
                    val hits = needleHits(apk, needles)
                    if (hits.isNotEmpty()) {
                        throw GradleException(
                            "verifyReleaseIdentity: ${apk.name} contains forbidden identity strings in " +
                                hits.joinToString() + " (INTERNAL-RULES 2)",
                        )
                    }
                    logger.lifecycle("verifyReleaseIdentity: ${apk.name} - DN clean, ${needles.size} needles absent from bytes and entries")
                }
            }
        }

        val verifyBundleIdentity = project.tasks.register("verifyBundleIdentity") {
            group = "verification"
            description = "Fails unless the release AAB is signed with the registered Play upload certificate."
            doLast {
                val pin = gates.bundleSignerSha256.orNull?.replace(":", "")?.uppercase()?.takeIf { it.length == 64 }
                    ?: throw GradleException("verifyBundleIdentity: classeveGates.bundleSignerSha256 must be a 64-hex SHA-256.")
                val needles = gates.forbiddenNeedlesBase64.get().map(::decodeNeedle)
                val aabs = listArtifacts(aabDir.get().asFile, ".aab", "verifyBundleIdentity", "AAB")
                for (aab in aabs) {
                    val actual = bundleSignerSha256(aab)
                    if (actual != pin) {
                        throw GradleException(
                            "verifyBundleIdentity: ${aab.name} is not signed with the registered Play upload certificate.\n" +
                                "  expected: $pin\n  actual:   $actual\n" +
                                "A bundle signed with any other key builds cleanly and is refused at upload with a 403.",
                        )
                    }
                    // The upload certificate carries the legal entity ON PURPOSE:
                    // Play App Signing strips it and re-signs before any device sees
                    // the app, and it is pinned by SHA-256 above. So the JAR signature
                    // block is the one place a needle is allowed; every other entry,
                    // the manifest, dex, resources and assets, must be clean.
                    val hits = needleHits(aab, needles, skipJarSignature = true)
                    if (hits.isNotEmpty()) {
                        throw GradleException(
                            "verifyBundleIdentity: ${aab.name} contains forbidden identity strings in " +
                                hits.joinToString() + " (INTERNAL-RULES 2)",
                        )
                    }
                    logger.lifecycle("verifyBundleIdentity: ${aab.name} - signed with the registered Play upload certificate, ${needles.size} needles absent outside the signature block")
                }
            }
        }

        val verifyReleaseHygiene = project.tasks.register("verifyReleaseHygiene") {
            group = "verification"
            description = "Fails if the release mapping shows a forbidden class survived shrinking."
            doLast {
                val file = mapping.get().asFile
                if (!file.isFile) {
                    throw GradleException("verifyReleaseHygiene: no release mapping at $file. Release builds must be minified.")
                }
                val prefixes = gates.forbiddenMappingPrefixes.get()
                val survivors = file.useLines { lines ->
                    lines.filter { line -> !line.startsWith(" ") && prefixes.any { line.startsWith(it) } }
                        .map { it.substringBefore(" ->") }
                        .toList()
                }
                if (survivors.isNotEmpty()) {
                    throw GradleException(
                        "verifyReleaseHygiene: the release still ships classes the shrinker should have dropped: " +
                            survivors.joinToString() + ". They are reachable from app code; remove the call.",
                    )
                }
                logger.lifecycle("verifyReleaseHygiene: none of ${prefixes.size} forbidden class prefixes in the release mapping")
            }
        }

        val verifyArtifactBudget = project.tasks.register("verifyArtifactBudget") {
            group = "verification"
            description = "Fails if the release APK, dex or AAB grows past release-budgets.properties."
            doLast {
                val budgets = project.file(gates.budgetsFile.get())
                if (!budgets.isFile) {
                    logger.warn("verifyArtifactBudget: NOT ENFORCED - no ${budgets.name} next to the app. Add apk.maxBytes, dex.maxBytes, aab.maxBytes.")
                    return@doLast
                }
                val props = Properties().apply { budgets.inputStream().use { load(it) } }
                fun limit(key: String) = props.getProperty(key)?.trim()?.toLongOrNull()
                val findings = mutableListOf<String>()
                fun check(label: String, actual: Long, max: Long?) {
                    if (max == null) return
                    val line = "$label ${actual.mb()} of ${max.mb()} budget"
                    if (actual > max) findings += "$line: OVER by ${(actual - max).mb()}"
                    else if (actual < max * 9 / 10) logger.lifecycle("verifyArtifactBudget: $line - tighten the budget to lock the gain in")
                    else logger.lifecycle("verifyArtifactBudget: $line")
                }
                apkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }?.sortedBy { it.name }?.forEach { apk ->
                    check("${apk.name} size", apk.length(), limit("apk.maxBytes"))
                    val dex = ZipFile(apk).use { zip -> zip.entries().asSequence().filter { it.name.endsWith(".dex") }.sumOf { it.size } }
                    check("${apk.name} dex", dex, limit("dex.maxBytes"))
                }
                aabDir.get().asFile.listFiles { f -> f.name.endsWith(".aab") }?.sortedBy { it.name }?.forEach { aab ->
                    check("${aab.name} size", aab.length(), limit("aab.maxBytes"))
                }
                if (findings.isNotEmpty()) {
                    throw GradleException("verifyArtifactBudget: " + findings.joinToString("\n  ", prefix = "\n  ") +
                        "\nA bigger artifact is a decision, not an accident: raise the budget in ${budgets.name} in the same commit, with the reason.")
                }
            }
        }

        project.tasks.matching { it.name == "assembleRelease" }.configureEach {
            dependsOn(requireCleanTree)
            finalizedBy(verifyReleaseIdentity, verifyReleaseHygiene, verifyArtifactBudget)
        }
        project.tasks.matching { it.name == "bundleRelease" }.configureEach {
            dependsOn(requireCleanTree)
            finalizedBy(verifyBundleIdentity, verifyReleaseHygiene, verifyArtifactBudget)
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun commitStamp(project: Project, rootDir: File): String {
        val head = runCatching { git(rootDir, "rev-parse", "--short=12", "HEAD").trim() }.getOrDefault("")
        if (head.isEmpty()) {
            project.logger.warn("classeveGates: not a git checkout, commit stamp is 'unknown'")
            return "unknown"
        }
        val dirty = runCatching { git(rootDir, "status", "--porcelain").lines().any { it.isNotBlank() } }.getOrDefault(true)
        return if (dirty) "$head-dirty" else head
    }

    private fun git(dir: File, vararg args: String): String =
        run(listOf("git", "-C", dir.absolutePath, *args))

    private fun run(vararg command: String): String = run(command.toList())

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw GradleException("${command.first()} failed: ${command.joinToString(" ")}\n$output")
        }
        return output
    }

    private fun javaBinary(): String {
        val home = File(System.getProperty("java.home"))
        val exe = File(home, "bin/java.exe").takeIf { it.isFile } ?: File(home, "bin/java")
        return exe.absolutePath
    }

    private fun sdkDirectory(rootDir: File): File? {
        val local = File(rootDir, "local.properties")
        val fromFile = if (local.isFile) {
            Properties().apply { local.inputStream().use { load(it) } }.getProperty("sdk.dir")
        } else null
        val candidate = fromFile ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        return candidate?.let(::File)?.takeIf { it.isDirectory }
    }

    private fun apksignerJar(sdk: File?): File {
        val buildTools = sdk?.let { File(it, "build-tools") }
            ?: throw GradleException("verifyReleaseIdentity: no Android SDK (sdk.dir in local.properties, or ANDROID_HOME). The gate fails closed.")
        return buildTools.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.reversed()
            ?.map { File(it, "lib/apksigner.jar") }?.firstOrNull { it.isFile }
            ?: throw GradleException("verifyReleaseIdentity: apksigner.jar not found under $buildTools. The gate fails closed.")
    }

    private fun listArtifacts(dir: File, suffix: String, gate: String, kind: String): List<File> {
        val files = dir.listFiles { f -> f.name.endsWith(suffix) }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) throw GradleException("$gate: no release $kind found in $dir")
        return files
    }

    private fun decodeNeedle(encoded: String): String = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)

    /** Compared as a SET of RDNs: apksigner and X500Principal print the same DN with different spacing. */
    private fun rdns(dn: String): Set<String> = dn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * Where each needle occurs: the raw file, and every zip entry inflated. Raw
     * bytes alone miss a string inside a deflated entry; entries alone miss the
     * signing block and anything stored outside the zip directory.
     */
    private fun needleHits(artifact: File, needles: List<String>, skipJarSignature: Boolean = false): List<String> {
        if (needles.isEmpty()) return emptyList()
        val hits = linkedSetOf<String>()
        if (!skipJarSignature) {
            val raw = String(artifact.readBytes(), Charsets.ISO_8859_1)
            if (needles.any { raw.contains(it) }) hits += "raw bytes"
        }
        ZipFile(artifact).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || entry.size > MaxScannedEntryBytes) continue
                if (skipJarSignature && isJarSignatureEntry(entry.name)) continue
                val text = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                if (needles.any { text.contains(it) }) hits += entry.name
            }
        }
        return hits.toList()
    }

    private fun isJarSignatureEntry(name: String): Boolean {
        val upper = name.uppercase()
        return upper.startsWith("META-INF/") &&
            (upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.endsWith(".SF") || upper == "META-INF/MANIFEST.MF")
    }

    /** The AAB is JAR-signed: the signer chain sits in the PKCS#7 block under META-INF, not in an APK signing block. */
    private fun bundleSignerSha256(aab: File): String {
        val block = ZipFile(aab).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull {
                val name = it.name.uppercase()
                name.startsWith("META-INF/") && (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))
            } ?: throw GradleException("verifyBundleIdentity: ${aab.name} carries no JAR signature block. An unsigned bundle is a build that lost its keystore.")
            zip.getInputStream(entry).use { it.readBytes() }
        }
        val signer = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(block))
            .filterIsInstance<X509Certificate>()
            .firstOrNull()
            ?: throw GradleException("verifyBundleIdentity: no signer certificate in ${aab.name}'s signature block.")
        return MessageDigest.getInstance("SHA-256").digest(signer.encoded).joinToString("") { "%02X".format(it) }
    }

    private fun Long.mb(): String = "%.2f MB".format(this / 1048576.0)

    private companion object {
        const val MaxScannedEntryBytes = 96L * 1024 * 1024
    }
}
