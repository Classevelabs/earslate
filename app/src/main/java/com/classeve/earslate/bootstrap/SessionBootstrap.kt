package com.classeve.earslate.bootstrap

import com.classeve.earslate.session.SessionPolicy
import com.classeve.earslate.session.TargetLanguage

/**
 * What the SessionCoordinator needs to open a Live session. Produced by a
 * [SessionBootstrapRepository].
 *
 * V1 dev mode populates [ephemeralToken] from local.properties GEMINI_API_KEY.
 * Production pulls it from the ClassEve Worker (see Lven-Infrastructure). The
 * *shape* of this object does not change between dev and prod — the swap
 * happens entirely inside the repository.
 */
data class SessionBootstrap(
    val ephemeralToken: String,
    val model: String,
    val targetLanguage: TargetLanguage,
    val voiceName: String?,
    val captionsEnabled: Boolean,
    val sessionPolicy: SessionPolicy,
    val source: BootstrapSource,
)

enum class BootstrapSource {
    /** local.properties — dev only, never in a shipped APK */
    LOCAL_DEV,

    /** ClassEve Worker /v1/earslate/bootstrap — production */
    REMOTE_WORKER,
}

interface SessionBootstrapRepository {
    /**
     * Fetches a fresh bootstrap. May hit the network (remote) or read from
     * BuildConfig (local dev). Throws [BootstrapException] on failure.
     */
    suspend fun bootstrap(): SessionBootstrap
}

class BootstrapException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
