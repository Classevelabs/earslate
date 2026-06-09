package com.classeve.earslate.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.classeve.earslate.auth.AuthSession
import com.classeve.earslate.auth.AuthStore
import com.classeve.earslate.auth.DeviceCodeResponse
import com.classeve.earslate.auth.DeviceLinkClient
import com.classeve.earslate.auth.PollResult
import com.classeve.earslate.ui.theme.EarslateTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sign-in via RFC 8628 device-authorization grant. Mirrors the Lven-Android
 * pairing flow but rendered in Compose with the ClassEve brand v6 (matte ember).
 *
 * State machine, all internal:
 *
 *   [Phase.Idle]      → user has not tapped sign-in. CTA visible.
 *   [Phase.Requesting]→ POSTing /v1/device/code.
 *   [Phase.Pairing]   → user-code visible, browser CTA visible, polling.
 *   [Phase.Expired]   → 15-min code lifetime hit; show retry.
 *   [Phase.Denied]    → user denied on the web; show retry.
 *   [Phase.Error]     → transient failure; show retry + message.
 */
@Composable
fun SignInScreen(
    onSignedIn: () -> Unit,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var pollJob by remember { mutableStateOf<Job?>(null) }

    // Cancel polling on screen leave so we don't leak a coroutine if the
    // caller navigates away mid-flow.
    DisposableEffect(Unit) {
        onDispose { pollJob?.cancel() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EarslateTheme.colors.canvas)
            .padding(padding)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Section meta-label: mono, uppercase, +0.12em tracking, textTertiary.
            Text(
                text = "EARSLATE / SIGN IN",
                style = EarslateTheme.textStyles.meta,
                color = EarslateTheme.colors.textTertiary,
            )

            // Brand hero. Space Grotesk bold, 36sp+, letter-spacing -1.5sp.
            Text(
                text = "Pair this device.",
                style = EarslateTheme.textStyles.display.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 40.sp,
                    lineHeight = 42.sp,
                    letterSpacing = (-1.5).sp,
                ),
                color = EarslateTheme.colors.textPrimary,
            )

            // Support copy: Inter regular, textSecondary.
            Text(
                text = "Translation runs through your ClassEve account so we can enforce your subscription and daily usage caps. Sign in once — this device stays paired until you sign out.",
                style = EarslateTheme.textStyles.body,
                color = EarslateTheme.colors.textSecondary,
            )

            Spacer(Modifier.height(8.dp))

            when (val current = phase) {
                Phase.Idle -> SignInCta(
                    onSignIn = {
                        scope.launch {
                            phase = Phase.Requesting
                            try {
                                val resp = DeviceLinkClient.requestCode()
                                openBrowser(context, resp.verificationUriComplete)
                                phase = Phase.Pairing(resp)
                                pollJob = scope.launch {
                                    runPolling(
                                        deviceCode = resp.deviceCode,
                                        interval = resp.interval,
                                        onAuthorized = { authorized ->
                                            persistSession(context, authorized)
                                            phase = Phase.Authorized
                                            onSignedIn()
                                        },
                                        onPhaseChange = { phase = it },
                                    )
                                }
                            } catch (t: Throwable) {
                                phase = Phase.Error(t.message ?: "Unable to start sign-in")
                            }
                        }
                    },
                )
                Phase.Requesting -> StatusPanel(message = "Preparing sign-in…")
                is Phase.Pairing -> PairingPanel(
                    response = current.response,
                    onOpenBrowser = { openBrowser(context, current.response.verificationUriComplete) },
                    onCancel = {
                        pollJob?.cancel()
                        pollJob = null
                        phase = Phase.Idle
                    },
                )
                Phase.Expired -> RetryPanel(
                    kicker = "CODE EXPIRED",
                    body = "The sign-in code is good for 15 minutes. Tap retry to start over.",
                    onRetry = { phase = Phase.Idle },
                )
                Phase.Denied -> RetryPanel(
                    kicker = "SIGN-IN DENIED",
                    body = "The sign-in was rejected on the web. Tap retry to try again.",
                    onRetry = { phase = Phase.Idle },
                )
                is Phase.Error -> RetryPanel(
                    kicker = "SIGN-IN FAILED",
                    body = current.message,
                    onRetry = { phase = Phase.Idle },
                )
                Phase.Authorized -> StatusPanel(message = "Signed in. Loading…")
            }
        }
    }
}

private sealed class Phase {
    data object Idle : Phase()
    data object Requesting : Phase()
    data class Pairing(val response: DeviceCodeResponse) : Phase()
    data object Expired : Phase()
    data object Denied : Phase()
    data class Error(val message: String) : Phase()
    data object Authorized : Phase()
}

@Composable
private fun SignInCta(onSignIn: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Tap below to start. We open your browser to classeve.com so you can confirm.",
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textSecondary,
        )
        EmberPill(
            label = "Open classeve.com/link",
            onClick = onSignIn,
        )
    }
}

@Composable
private fun PairingPanel(
    response: DeviceCodeResponse,
    onOpenBrowser: () -> Unit,
    onCancel: () -> Unit,
) {
    // Code-band: bg-elev-1 flat fill, no border, rounded-lg.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = EarslateTheme.colors.elev1,
                shape = EarslateTheme.shapes.lg,
            )
            .padding(horizontal = 22.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "YOUR CODE",
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.textTertiary,
        )
        // Code display — JetBrains Mono, large (40sp), ember color, +0.18em tracking.
        Text(
            text = response.userCode,
            style = EarslateTheme.textStyles.meta.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp,
                lineHeight = 44.sp,
                letterSpacing = 7.2.sp, // ≈ +0.18em at 40sp
            ),
            color = EarslateTheme.colors.ember,
        )
        Text(
            text = "We've opened your browser to classeve.com. Confirm the code there to finish signing in.",
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(4.dp))
        EmberPill(
            label = "Open browser again",
            onClick = onOpenBrowser,
        )
        SecondaryPill(
            label = "Cancel",
            onClick = onCancel,
        )
    }
}

@Composable
private fun StatusPanel(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = EarslateTheme.colors.elev1,
                shape = EarslateTheme.shapes.lg,
            )
            .padding(horizontal = 22.dp, vertical = 26.dp),
    ) {
        Text(
            text = message,
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun RetryPanel(
    kicker: String,
    body: String,
    onRetry: () -> Unit,
) {
    // Hard-error band — oxblood-soft flat fill, cream text, ember retry CTA.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = EarslateTheme.colors.oxbloodSoft,
                shape = EarslateTheme.shapes.lg,
            )
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = kicker,
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.creamSoft,
        )
        Text(
            text = body,
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.cream,
        )
        EmberPill(
            label = "Try again",
            onClick = onRetry,
        )
    }
}

/** Primary CTA — ember pill with onEmber text, full-width. */
@Composable
private fun EmberPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = EarslateTheme.colors.ember, shape = EarslateTheme.shapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = EarslateTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
            color = EarslateTheme.colors.onEmber,
        )
    }
}

/** Secondary CTA — bg-elev-2 pill with cream text. Flat, no border. */
@Composable
private fun SecondaryPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = EarslateTheme.colors.elev2, shape = EarslateTheme.shapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.cream,
        )
    }
}

/**
 * Polls the worker every [interval] seconds, respecting the slow_down signal
 * that asks the client to back off by 5 s. Calls back into UI state through
 * [onPhaseChange]; on success runs [onAuthorized] which persists the session.
 */
private suspend fun runPolling(
    deviceCode: String,
    interval: Int,
    onAuthorized: (PollResult.Authorized) -> Unit,
    onPhaseChange: (Phase) -> Unit,
) {
    var currentInterval = interval.toLong().coerceAtLeast(1L)
    while (true) {
        delay(currentInterval * 1000L)
        try {
            when (val result = DeviceLinkClient.poll(deviceCode)) {
                is PollResult.Authorized -> {
                    onAuthorized(result)
                    return
                }
                PollResult.Pending -> { /* keep polling */ }
                PollResult.SlowDown -> currentInterval += 5
                PollResult.Expired -> {
                    onPhaseChange(Phase.Expired)
                    return
                }
                PollResult.Denied -> {
                    onPhaseChange(Phase.Denied)
                    return
                }
            }
        } catch (_: Exception) {
            // Network blip — try again on the next interval.
        }
    }
}

/** Persists the session and decodes email from the JWT (best effort). */
private fun persistSession(
    context: android.content.Context,
    authorized: PollResult.Authorized,
) {
    val email = decodeJwtEmail(authorized.accessToken)
    AuthStore.save(
        context = context,
        session = AuthSession(
            accessToken = authorized.accessToken,
            refreshToken = authorized.refreshToken,
            expiresAtEpochMs = System.currentTimeMillis() + authorized.expiresInSeconds * 1000L,
            email = email,
        ),
    )
}

private fun decodeJwtEmail(token: String): String? = try {
    val parts = token.split(".")
    if (parts.size != 3) null else {
        val payload = String(
            android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING),
            Charsets.UTF_8,
        )
        org.json.JSONObject(payload).optString("email").ifBlank { null }
    }
} catch (_: Exception) {
    null
}

/**
 * Open the verification URL. Prefer Custom Tabs if androidx.browser is on the
 * classpath; otherwise fall back to a plain ACTION_VIEW. Today earslate does
 * not bundle androidx.browser, so we use the fallback.
 */
private fun openBrowser(context: android.content.Context, url: String) {
    // Defense-in-depth: the verification URI arrives from the Worker over
    // TLS, but never hand a non-https URI to ACTION_VIEW — a crafted scheme
    // (intent://, file://) could route into arbitrary exported components.
    if (!url.startsWith("https://")) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
