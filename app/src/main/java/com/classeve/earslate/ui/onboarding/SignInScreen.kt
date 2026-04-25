package com.classeve.earslate.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classeve.earslate.auth.AuthSession
import com.classeve.earslate.auth.AuthStore
import com.classeve.earslate.auth.DeviceCodeResponse
import com.classeve.earslate.auth.DeviceLinkClient
import com.classeve.earslate.auth.PollResult
import com.classeve.earslate.ui.components.SectionHeader
import com.classeve.earslate.ui.theme.EarslateTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sign-in via RFC 8628 device-authorization grant. Mirrors the Lven-Android
 * pairing flow but rendered in Compose to match earslate's UI conventions.
 *
 * State machine, all internal:
 *
 *   [Phase.Idle]      → user has not tapped sign-in. CTA visible.
 *   [Phase.Requesting]→ POSTing /v1/device/code; spinner.
 *   [Phase.Pairing]   → user-code visible, browser CTA visible, polling.
 *   [Phase.Expired]   → 15-min code lifetime hit; show retry.
 *   [Phase.Denied]    → user denied on the web; show retry.
 *   [Phase.Error]     → transient failure; show retry + message.
 *
 * On [Phase.Authorized] we persist the session via [AuthStore.save] then call
 * [onSignedIn] — the parent activity navigates to the main UI from there.
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
    // caller navigates away mid-flow. DisposableEffect with Unit key fires
    // its onDispose only when the composable leaves the tree — exactly the
    // semantics we want for the *current* pollJob, not a snapshot of it.
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
            Text(
                text = "EARSLATE",
                style = EarslateTheme.textStyles.kicker,
                color = EarslateTheme.colors.textTertiary,
            )

            SectionHeader(
                kicker = "Sign in",
                headline = "Sign in with classeve.com.",
                support = "Translation runs through your ClassEve account so we can enforce your subscription and daily usage caps.",
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
                    title = "Code expired",
                    body = "The sign-in code is good for 15 minutes. Tap retry to start over.",
                    onRetry = { phase = Phase.Idle },
                )
                Phase.Denied -> RetryPanel(
                    title = "Sign-in denied",
                    body = "The sign-in was rejected on the web. Tap retry to try again.",
                    onRetry = { phase = Phase.Idle },
                )
                is Phase.Error -> RetryPanel(
                    title = "Sign-in failed",
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
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Tap below to start. We open your browser to classeve.com so you can confirm.",
            style = EarslateTheme.textStyles.bodyMuted,
            color = EarslateTheme.colors.textSecondary,
        )
        Button(
            onClick = onSignIn,
            colors = ButtonDefaults.buttonColors(
                containerColor = EarslateTheme.colors.accent,
                contentColor = EarslateTheme.colors.canvas,
            ),
            shape = EarslateTheme.shapes.md,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Sign in with classeve.com",
                style = EarslateTheme.textStyles.body.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
private fun PairingPanel(
    response: DeviceCodeResponse,
    onOpenBrowser: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = EarslateTheme.colors.surfaceSoft,
                shape = EarslateTheme.shapes.md,
            )
            .border(
                width = 1.dp,
                color = EarslateTheme.colors.borderSubtle,
                shape = EarslateTheme.shapes.md,
            )
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "YOUR CODE",
            style = EarslateTheme.textStyles.kicker,
            color = EarslateTheme.colors.textTertiary,
        )
        Text(
            text = response.userCode,
            style = EarslateTheme.textStyles.display,
            color = EarslateTheme.colors.accent,
        )
        Text(
            text = "We've opened your browser to classeve.com. Confirm the code there to finish signing in.",
            style = EarslateTheme.textStyles.bodyMuted,
            color = EarslateTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onOpenBrowser,
            colors = ButtonDefaults.buttonColors(
                containerColor = EarslateTheme.colors.accent,
                contentColor = EarslateTheme.colors.canvas,
            ),
            shape = EarslateTheme.shapes.md,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Open browser again",
                style = EarslateTheme.textStyles.body.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        OutlinedButton(
            onClick = onCancel,
            shape = EarslateTheme.shapes.md,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Cancel",
                style = EarslateTheme.textStyles.body,
                color = EarslateTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun StatusPanel(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = EarslateTheme.colors.surfaceSoft,
                shape = EarslateTheme.shapes.md,
            )
            .border(
                width = 1.dp,
                color = EarslateTheme.colors.borderSubtle,
                shape = EarslateTheme.shapes.md,
            )
            .padding(horizontal = 18.dp, vertical = 22.dp),
    ) {
        Text(
            text = message,
            style = EarslateTheme.textStyles.bodyMuted,
            color = EarslateTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun RetryPanel(
    title: String,
    body: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = EarslateTheme.colors.errorBg,
                shape = EarslateTheme.shapes.md,
            )
            .border(
                width = 1.dp,
                color = EarslateTheme.colors.errorBorder,
                shape = EarslateTheme.shapes.md,
            )
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = EarslateTheme.textStyles.kicker,
            color = EarslateTheme.colors.danger,
        )
        Text(
            text = body,
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textPrimary,
        )
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = EarslateTheme.colors.accent,
                contentColor = EarslateTheme.colors.canvas,
            ),
            shape = EarslateTheme.shapes.md,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Try again",
                style = EarslateTheme.textStyles.body.copy(fontWeight = FontWeight.SemiBold),
            )
        }
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
 * not bundle androidx.browser, so we use the fallback. The integration plan
 * §5 mentions Custom Tabs — easy upgrade later.
 */
private fun openBrowser(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

