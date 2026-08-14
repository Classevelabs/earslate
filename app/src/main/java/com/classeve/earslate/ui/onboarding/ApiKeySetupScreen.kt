package com.classeve.earslate.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectable
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
 * Where the user supplies the API key that runs their translations.
 *
 * earslate has no server, so this key is the whole account system. That makes
 * this screen the one place the app can lose someone entirely, and it is
 * written accordingly:
 *
 *  - The provider is chosen first, because the instructions and the key format
 *    both depend on it.
 *  - The steps to get a key are on this screen, numbered, with a button that
 *    opens the right console — not a link to a help page.
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
    targetLanguageCode: String,
    initialProvider: KeyProvider = KeyProvider.GEMINI,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys = remember { EarslateRuntime.providerKeys(context) }
    val verifier = remember { EarslateRuntime.keyVerifier(context) }

    var provider by remember { mutableStateOf(initialProvider) }
    var keyText by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(keys.configured()) }

    // If a pasted key clearly belongs to the other provider, switch to it — but
    // only when we recognise it. An unrecognised key is not an error: provider
    // key formats change, and the provider itself is the only real judge.
    LaunchedEffect(keyText) {
        val detected = KeyProvider.detect(keyText)
        if (detected != null && detected != provider) provider = detected
        problem = null
        hint = KeyProvider.forProvider(provider.provider)?.looksLikeAnotherProvider(keyText)
            ?.let { "This looks like a ${it.displayName} key. If that's right, pick ${it.displayName} above — otherwise carry on, we'll check it with ${provider.displayName}." }
    }

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
                            saved = keys.configured()
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
                    "and the provider you choose, billed to your own account. Your key is " +
                    "encrypted on this device and never sent anywhere else.",
            )

            SectionHeader(
                kicker = "Step 1",
                headline = "Choose a provider.",
                support = "Gemini translates both directions at once, so two people can talk " +
                    "naturally. OpenAI translates into one language at a time.",
            )

            FramedPanel {
                KeyProvider.entries.forEachIndexed { index, option ->
                    if (index > 0) ThinDivider()
                    ProviderRow(
                        provider = option,
                        selected = provider == option,
                        alreadySaved = saved.contains(option),
                        onSelect = { provider = option },
                    )
                }
            }

            SectionHeader(
                kicker = "Step 2",
                headline = "Get a key.",
                support = "It takes about a minute. You only do this once.",
            )

            FramedPanel {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    instructionsFor(provider).forEachIndexed { index, line ->
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
                kicker = "Step 3",
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

                    // A hint, not a refusal — the button stays enabled.
                    if (problem == null) {
                        hint?.let { message ->
                            Text(
                                text = message,
                                style = EarslateTheme.textStyles.bodySmall,
                                color = EarslateTheme.colors.textTertiary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
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
                // server, which is the claim actually worth making. Conflating
                // "not backed up" with "never transmitted" is the kind of
                // sentence a user would be right to feel misled by.
                text = "Your key is sealed by this device's hardware keystore, and is excluded " +
                    "from Android backups and device-to-device transfer. It is sent only to " +
                    "the provider you chose — over an encrypted connection, once per session, " +
                    "to open that session. It is never sent to ClassEve.",
                style = EarslateTheme.textStyles.bodySmall,
                color = EarslateTheme.colors.textTertiary,
                textAlign = TextAlign.Start,
            )
        }
    }
}

private fun instructionsFor(provider: KeyProvider): List<String> = when (provider) {
    // Deliberately no claim about what a key looks like. Google has changed
    // that before, and telling someone their valid key is wrong is worse than
    // telling them nothing.
    KeyProvider.GEMINI -> listOf(
        "Open Google AI Studio and sign in with a Google account.",
        "Select “Get API key”, then “Create API key”.",
        "Pick a Google Cloud project, or let it make one for you.",
        "Copy the whole key it shows you and paste it below.",
    )

    KeyProvider.OPENAI -> listOf(
        "Open the OpenAI dashboard and sign in.",
        "Go to API keys, then “Create new secret key”.",
        "Copy it straight away — OpenAI shows a secret key only once.",
        "Make sure the account has billing set up, or live translation will be refused.",
    )
}

@Composable
private fun ProviderRow(
    provider: KeyProvider,
    selected: Boolean,
    alreadySaved: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.displayName,
                style = EarslateTheme.textStyles.body,
                color = if (selected) {
                    EarslateTheme.colors.ember
                } else {
                    EarslateTheme.colors.textPrimary
                },
            )
            if (alreadySaved) {
                Text(
                    text = "Key saved",
                    style = EarslateTheme.textStyles.bodySmall,
                    color = EarslateTheme.colors.textTertiary,
                )
            }
        }
        Text(
            text = if (selected) "SELECTED" else "",
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.ember,
            modifier = Modifier.clearAndSetSemantics { },
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

@Composable
private fun ThinDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color = EarslateTheme.colors.borderSubtle),
    )
}
