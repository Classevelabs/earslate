package com.classeve.earslate.update

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.classeve.earslate.BuildConfig
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** What classeve.com says about the newest earslate: `/apps/earslate.json`, written from the site's product list. */
@Serializable
internal data class SitePolicy(
    val version: String,
    val minimumVersion: String? = null,
    val url: String,
)

/** What the screen shows about an update from classeve.com. */
sealed interface SiteGate {
    data object None : SiteGate
    data class Available(val version: String, val url: String) : SiteGate
    data class Required(val version: String, val url: String) : SiteGate
}

internal const val POLICY_URL = "https://classeve.com/apps/earslate.json"

// The only place the update button may send someone: a changed or forged file
// cannot point it anywhere but our own downloads.
private const val DOWNLOADS = "https://classeve.com/downloads/"

private val policyJson = Json { ignoreUnknownKeys = true }

internal fun parsePolicy(text: String): SitePolicy? =
    runCatching { policyJson.decodeFromString(SitePolicy.serializer(), text) }.getOrNull()
        ?.takeIf { it.url.startsWith(DOWNLOADS) && versionParts(it.version) != null }

/** x.y.z as numbers; a suffix after a hyphen (a debug build's) is not part of the version. */
internal fun versionParts(version: String): List<Int>? =
    version.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

internal fun isOlder(version: String, than: String): Boolean {
    val a = versionParts(version) ?: return false
    val b = versionParts(than) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}

internal fun siteGateFor(own: String, policy: SitePolicy?, dismissed: String?): SiteGate = when {
    policy == null -> SiteGate.None
    policy.minimumVersion != null && isOlder(own, policy.minimumVersion) -> SiteGate.Required(policy.version, policy.url)
    isOlder(own, policy.version) && policy.version != dismissed -> SiteGate.Available(policy.version, policy.url)
    else -> SiteGate.None
}

/**
 * Updates for a copy installed from classeve.com, which Google Play cannot
 * update. Once a UTC day it reads one public file, sending nothing but the
 * request and earslate's version as its user agent; between reads the last
 * answer stands, so a required update cannot be waited out. A Play copy never
 * asks: Play's own updates serve it.
 */
class SiteUpdates(
    private val activity: ComponentActivity,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
) {
    private val prefs by lazy { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val gateState = MutableStateFlow<SiteGate>(SiteGate.None)
    val gate: StateFlow<SiteGate> = gateState.asStateFlow()

    init {
        if (BuildConfig.FROM_SITE) {
            activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) = check()
            })
        }
    }

    private fun check() {
        show()
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        if (prefs.getString(KEY_DAY, null) == today) return
        activity.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { read() } ?: return@launch
            if (parsePolicy(text) == null) return@launch
            prefs.edit().putString(KEY_DAY, today).putString(KEY_POLICY, text).apply()
            show()
        }
    }

    private fun read(): String? = runCatching {
        val request = Request.Builder()
            .url(POLICY_URL)
            .header("User-Agent", "earslate/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
    }.getOrNull()

    private fun show() {
        val policy = prefs.getString(KEY_POLICY, null)?.let(::parsePolicy)
        gateState.value = siteGateFor(BuildConfig.VERSION_NAME, policy, prefs.getString(KEY_DISMISSED, null))
    }

    /** "Later" on an update that is offered: it stays away until a newer one is out. */
    fun later() {
        val offered = gateState.value as? SiteGate.Available ?: return
        prefs.edit().putString(KEY_DISMISSED, offered.version).apply()
        show()
    }

    private companion object {
        const val PREFS = "earslate_updates"
        const val KEY_DAY = "checked_day"
        const val KEY_POLICY = "policy"
        const val KEY_DISMISSED = "dismissed_version"
    }
}
