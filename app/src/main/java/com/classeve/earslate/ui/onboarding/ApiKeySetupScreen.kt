package com.classeve.earslate.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.classeve.earslate.EarslateRuntime
import com.classeve.earslate.bootstrap.ProviderKeyVerifier
import com.classeve.earslate.security.KeyProvider
import com.classeve.earslate.security.KeyVault
import com.classeve.earslate.ui.components.BackRow
import com.classeve.earslate.ui.components.EmberButton
import com.classeve.earslate.ui.components.FramedPanel
import com.classeve.earslate.ui.components.SectionHeader
import com.classeve.earslate.ui.theme.EarslateTheme
import kotlinx.coroutines.launch

/**
 * Where the user supplies the Gemini API key that runs their translations.
 *
 * earslate has no server, so this key is the whole account system. That makes
 * this screen the one place the app can lose someone entirely, and it is
 * written accordingly:
 *
 *  - The steps to get a key are on this screen, numbered, with a button that
 *    opens Google AI Studio — not a link to a help page.
 *  - Format problems are named specifically the moment they are visible
 *    ("that's a web address", "remove the Bearer prefix") rather than a generic
 *    "invalid key".
 *  - **The key is verified against the provider before it is saved.** A format
 *    check only proves a string is shaped right. Minting a real session proves
 *    the key is accepted, the account has billing, and the live translation
 *    model is actually reachable — the three things that otherwise fail
 *    mid-conversation, when the user can do least about it.
 */
@Composable
fun ApiKeySetupScreen(
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
    /**
     * Fired when the set of stored keys changes without leaving this screen,
     * which is what removing one does. The host tracks "does the app have a
     * key at all" and only recomputed it on navigation.
     */
    onKeysChanged: () -> Unit = {},
    targetLanguageCode: String,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys = remember { EarslateRuntime.providerKeys(context) }
    val verifier = remember { EarslateRuntime.keyVerifier(context) }

    // The app talks only to Gemini, so there is nothing to choose here.
    val provider = KeyProvider.GEMINI
    var keyText by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var keySaved by remember { mutableStateOf(keys.has(provider)) }

    /**
     * Explains a key that vanished on its own.
     *
     * When the platform destroys the encryption key, every stored secret becomes
     * unreadable and KeyVault clears it — by design, and it records the fact in
     * `wasResetByKeystore` precisely so this could be explained. Nothing ever
     * read that flag: the accessor and `acknowledgeKeystoreReset()` were both
     * dead code, so the app simply saw "no key" and dropped the user back here
     * with no explanation, as though it had forgotten what they pasted for no
     * reason. Reading it once, on arrival, is the whole fix — and acknowledging
     * it means the notice appears once rather than on every visit.
     */
    val keystoreReset = remember {
        keys.wasResetByKeystore().also { if (it) keys.acknowledgeKeystoreReset() }
    }

    LaunchedEffect(keyText) { problem = null }

    fun submit() {
        val candidate = keyText.trim()
        val formatProblem = provider.rejectionReason(candidate)
        if (formatProblem != null) {
            problem = formatProblem
            return
        }
        checking = true
        problem = null
        scope.launch {
            when (val result = verifier.verify(provider, candidate, targetLanguageCode)) {
                is ProviderKeyVerifier.Result.Valid -> {
                    // A write to the vault fails closed by design, and nothing
                    // caught it: VaultUnavailable is a RuntimeException with no
                    // handler anywhere in the app, thrown from inside this
                    // coroutine. The user had just watched their key be verified
                    // against the live provider, and the app died at the moment
                    // it went to store it — the worst possible instant, and with
                    // no message. Failing closed is right; failing silently and
                    // then crashing is not the same thing.
                    val stored = runCatching { keys.save(provider, candidate) }
                    checking = false
                    stored.fold(
                        onSuccess = {
                            keyText = ""
                            keySaved = keys.has(provider)
                            onDone()
                        },
                        onFailure = { failure ->
                            problem = (failure as? KeyVault.VaultUnavailable)?.message
                                ?: "That key could not be saved on this device."
                        },
                    )
                }

                is ProviderKeyVerifier.Result.Rejected -> {
                    checking = false
                    problem = result.message
                }
            }
        }
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
                .padding(horizontal = 24.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (onBack != null) BackRow(onBack = onBack)

            SectionHeader(
                kicker = "Setup",
                headline = "Your key, your account.",
                support = "earslate has no servers. Translation runs directly between your phone " +
                    "and Google Gemini, billed to your own account. Your key is encrypted on " +
                    "this device and never sent anywhere else.",
            )

            if (keystoreReset) {
                FramedPanel {
                    Text(
                        text = "Your saved key was cleared because this device's secure storage " +
                            "was reset. Nothing was sent anywhere — the stored copy simply became " +
                            "unreadable, and it can't be recovered. Paste it again below.",
                        style = EarslateTheme.textStyles.bodySmall,
                        color = EarslateTheme.colors.textSecondary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (keySaved) {
                FramedPanel {
                    SavedKeyRow(
                        provider = provider,
                        onForget = {
                            keys.forget(provider)
                            keySaved = keys.has(provider)
                            problem = null
                            onKeysChanged()
                        },
                    )
                }
            }

            SectionHeader(
                kicker = "Step 1",
                headline = "Get a key.",
                support = "It takes about a minute. You only do this once.",
            )

            FramedPanel {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GEMINI_INSTRUCTIONS.forEachIndexed { index, line ->
                        NumberedStep(index + 1, line)
                    }
                    Spacer(Modifier.height(4.dp))
                    EmberButton(
                        label = "Open ${provider.consoleName}",
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(provider.consoleUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SectionHeader(
                kicker = "Step 2",
                headline = "Paste it here.",
                support = "We'll check it works before saving — so it can't fail later, " +
                    "mid-conversation.",
            )

            FramedPanel {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "${provider.displayName} API key"
                            },
                        singleLine = true,
                        enabled = !checking,
                        isError = problem != null,
                        placeholder = { Text(provider.placeholder) },
                        visualTransformation = if (revealed) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { revealed = !revealed }) {
                            Text(
                                text = if (revealed) "Hide key" else "Show key",
                                style = EarslateTheme.textStyles.bodySmall,
                                color = EarslateTheme.colors.textSecondary,
                            )
                        }
                        if (checking) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = EarslateTheme.colors.ember,
                                )
                                Text(
                                    text = "Checking…",
                                    style = EarslateTheme.textStyles.bodySmall,
                                    color = EarslateTheme.colors.textSecondary,
                                )
                            }
                        }
                    }

                    problem?.let { message ->
                        Text(
                            text = message,
                            style = EarslateTheme.textStyles.bodySmall,
                            color = EarslateTheme.colors.ember,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }

                    EmberButton(
                        label = if (checking) "Checking…" else "Verify and save",
                        onClick = { if (!checking) submit() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Text(
                // "never leaves this phone" was false, on the one screen where
                // being trusted matters most. The key IS sent — to the provider,
                // over HTTPS, once per session, to mint the short-lived
                // credential the socket then uses. What never leaves the device
                // is the STORED copy: no backup, no transfer, and no ClassEve
                // server, which is the claim actually worth making.
                text = "Your key is sealed by this device's hardware keystore, and is excluded " +
                    "from Android backups and device-to-device transfer. It is sent only to " +
                    "Google Gemini — over an encrypted connection, once per session, to open " +
                    "that session. It is never sent to ClassEve.",
                style = EarslateTheme.textStyles.bodySmall,
                color = EarslateTheme.colors.textTertiary,
                textAlign = TextAlign.Start,
            )
        }
    }
}

// Deliberately no claim about what a key looks like. Google has changed that
// before, and telling someone their valid key is wrong is worse than telling
// them nothing.
private val GEMINI_INSTRUCTIONS = listOf(
    "Open Google AI Studio and sign in with a Google account.",
    "Select “Get API key”, then “Create API key”.",
    "Pick a Google Cloud project, or let it make one for you.",
    "Copy the whole key it shows you and paste it below.",
)

@Composable
private fun SavedKeyRow(
    provider: KeyProvider,
    /**
     * Removes the stored key.
     *
     * ProviderKeyStore.forget() existed and had no caller anywhere in the app,
     * so there was no way to take a key off the device at all: this screen can
     * overwrite one but never delete it, and the Settings row only reopens this
     * screen. Someone selling a phone, handing it over, or rotating a key had
     * only "clear app data" — which also destroys their languages and
     * onboarding — or uninstalling.
     */
    onForget: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.displayName,
                style = EarslateTheme.textStyles.body,
                color = EarslateTheme.colors.textPrimary,
            )
            Text(
                text = "Key saved",
                style = EarslateTheme.textStyles.bodySmall,
                color = EarslateTheme.colors.textTertiary,
            )
        }
        Text(
            text = "REMOVE",
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.textTertiary,
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .clickable(role = Role.Button, onClick = onForget)
                .padding(horizontal = 8.dp, vertical = 14.dp)
                .semantics {
                    contentDescription = "Remove the saved ${provider.displayName} key"
                },
        )
    }
}

@Composable
private fun NumberedStep(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "$number",
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.ember,
            modifier = Modifier
                .width(16.dp)
                .clearAndSetSemantics { },
        )
        Text(
            text = text,
            style = EarslateTheme.textStyles.bodySmall,
            color = EarslateTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}
