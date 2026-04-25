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

/**
 * Generic bootstrap failure. Specific failure modes that the UI must branch on
 * (subscription required, daily limit hit, sign-in needed) are surfaced as
 * subclasses below so [SessionCoordinator] can map them to typed
 * [com.classeve.earslate.session.RuntimeError.Kind]s without re-parsing the
 * exception message.
 */
open class BootstrapException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Worker returned 402 SUBSCRIPTION_REQUIRED or ENTITLEMENT_MISSING — the user's
 * plan does not include translate. UI must show a "Subscription required"
 * dialog with a "View plans" CTA. The destination URL lives in the UI layer
 * (MainActivity) since the worker has no opinion on which marketing surface
 * a given product should link to.
 */
class SubscriptionRequiredException(
    message: String,
) : BootstrapException(message)

/**
 * Worker returned 429 DAILY_LIMIT_REACHED. UI must surface "Daily limit
 * reached" and stop the session. Resets at 00:00 UTC; we don't currently
 * receive an explicit reset timestamp, but the message is rendered.
 */
class DailyLimitReachedException(
    message: String,
    val dailyCapSeconds: Int? = null,
    val dailyUsedSeconds: Int? = null,
) : BootstrapException(message)

/**
 * Worker returned 401 (or refresh failed locally). The stored session is
 * invalid — UI must clear it and bounce the user back to the sign-in screen.
 */
class AuthRequiredException(
    message: String = "Sign-in required — please pair this device again.",
) : BootstrapException(message)
