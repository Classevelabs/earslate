package com.classeve.earslate.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.classeve.earslate.auth.GeminiKeyStore
import com.classeve.earslate.ui.components.BackRow
import com.classeve.earslate.ui.components.EmberButton
import com.classeve.earslate.ui.components.FramedPanel
import com.classeve.earslate.ui.components.SectionHeader
import com.classeve.earslate.ui.theme.EarslateTheme
import com.classeve.earslate.ui.theme.MotionBaseMs
import com.classeve.earslate.ui.theme.MotionSlowMs
import com.classeve.earslate.ui.theme.PreciseEasing
import com.classeve.earslate.ui.theme.rememberReducedMotion

private const val AI_STUDIO_KEY_URL = "https://aistudio.google.com/apikey"

/**
 * Bring-your-own-key setup. earslate has no account and no server: the user
 * pastes their own Gemini API key, which is stored only in [GeminiKeyStore]
 * (EncryptedSharedPreferences, on-device) and used to connect directly to
 * Google's Gemini Live endpoint. ClassEve never sees it.
 *
 * Two framings:
 *  - First run (no key stored, [onBack] hidden): "last step" language, the
 *    full numbered how-to for a non-technical person.
 *  - Re-entry via Settings / the missing-key banner (key stored): shows the
 *    saved-key state with a masked suffix, plus replace/remove affordances.
 */
@Composable
fun ApiKeySetupScreen(
    onKeySaved: () -> Unit,
    padding: PaddingValues = PaddingValues(0.dp),
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    var storedKey by remember { mutableStateOf(GeminiKeyStore.load(context)) }
    val hasExistingKey = storedKey != null

    var keyInput by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    fun attemptSave() {
        val trimmed = keyInput.trim()
        errorText = validateKeyShape(trimmed)
        if (errorText == null) {
            GeminiKeyStore.save(context, trimmed)
            onKeySaved()
        }
    }

    fun openAiStudio() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_KEY_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            containerColor = EarslateTheme.colors.elev2,
            titleContentColor = EarslateTheme.colors.textPrimary,
            textContentColor = EarslateTheme.colors.textSecondary,
            title = { Text("Remove your API key?") },
            text = {
                Text(
                    "earslate can't translate without a key. You can paste the " +
                        "same key again later — removing it here doesn't delete " +
                        "it from your Google account.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    GeminiKeyStore.clear(context)
                    storedKey = null
                    keyInput = ""
                    errorText = null
                    showRemoveDialog = false
                }) {
                    Text("Remove", color = EarslateTheme.colors.ember)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text("Keep it", color = EarslateTheme.colors.textSecondary)
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EarslateTheme.colors.canvas)
            .padding(padding)
            .statusBarsPadding(),
    ) {
        // Gentle one-shot entrance reveal; skipped entirely when the system
        // "remove animations" accessibility setting is on.
        Reveal {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (onBack != null && hasExistingKey) {
                    BackRow(onBack = onBack)
                }

                Text(
                    text = if (hasExistingKey) "EARSLATE / API KEY" else "EARSLATE / LAST STEP",
                    style = EarslateTheme.textStyles.meta,
                    color = EarslateTheme.colors.textTertiary,
                )

                SectionHeader(
                    kicker = if (hasExistingKey) "Your key" else "One key and you're in",
                    headline = if (hasExistingKey) "Manage your\nGemini key." else "Add your free\nGemini key.",
                    support = if (hasExistingKey) {
                        "Replace or remove the key earslate uses to talk to Google. " +
                            "It never leaves this phone."
                    } else {
                        "earslate is free and has no accounts. It translates using a " +
                            "free Google Gemini key that you create yourself — it takes " +
                            "about two minutes and stays on this phone."
                    },
                )

                if (hasExistingKey) {
                    SavedKeyCard(
                        maskedSuffix = storedKey.orEmpty().takeLast(4),
                        onRemove = { showRemoveDialog = true },
                    )
                }

                EmberButton(
                    label = "Create your free Gemini key",
                    onClick = ::openAiStudio,
                )

                FramedPanel {
                    Text(
                        text = "HOW TO GET ONE",
                        style = EarslateTheme.textStyles.meta,
                        color = EarslateTheme.colors.textTertiary,
                    )
                    NumberedStep(1, "Tap “Create your free Gemini key” above. It opens Google AI Studio in your browser.")
                    NumberedStep(2, "Sign in with your Google account — the same one you use for Gmail.")
                    NumberedStep(3, "Tap “Create API key” on that page.")
                    NumberedStep(4, "Copy the key it shows you. It starts with “AIza”.")
                    NumberedStep(5, "Come back here and paste it below.")
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = {
                            keyInput = it
                            // Clear a stale error as soon as the user edits —
                            // don't nag while they're still typing/pasting.
                            if (errorText != null) errorText = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp),
                        label = {
                            Text(if (hasExistingKey) "Paste a new Gemini API key" else "Paste your Gemini API key")
                        },
                        placeholder = {
                            Text(
                                text = "AIza…",
                                color = EarslateTheme.colors.textTertiary,
                            )
                        },
                        singleLine = true,
                        isError = errorText != null,
                        supportingText = {
                            val err = errorText
                            if (err != null) {
                                Text(
                                    text = err,
                                    color = EarslateTheme.colors.danger,
                                    style = EarslateTheme.textStyles.bodySmall,
                                )
                            }
                        },
                        visualTransformation = if (keyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { keyVisible = !keyVisible },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = if (keyVisible) {
                                        Icons.Outlined.VisibilityOff
                                    } else {
                                        Icons.Outlined.Visibility
                                    },
                                    contentDescription = if (keyVisible) "Hide key" else "Show key",
                                    tint = EarslateTheme.colors.textSecondary,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { attemptSave() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = EarslateTheme.colors.textPrimary,
                            unfocusedTextColor = EarslateTheme.colors.textPrimary,
                            cursorColor = EarslateTheme.colors.ember,
                            focusedBorderColor = EarslateTheme.colors.ember,
                            unfocusedBorderColor = EarslateTheme.colors.borderDefault,
                            errorBorderColor = EarslateTheme.colors.oxblood,
                            errorCursorColor = EarslateTheme.colors.oxblood,
                            focusedLabelColor = EarslateTheme.colors.ember,
                            unfocusedLabelColor = EarslateTheme.colors.textTertiary,
                            errorLabelColor = EarslateTheme.colors.danger,
                        ),
                    )

                    // Extra inline confirmation once the paste looks right —
                    // quiet positive feedback before the user commits.
                    AnimatedVisibility(
                        visible = errorText == null &&
                            validateKeyShape(keyInput.trim()) == null &&
                            keyInput.isNotBlank(),
                        enter = expandVertically(tween(MotionBaseMs, easing = PreciseEasing)) +
                            fadeIn(tween(MotionBaseMs)),
                        exit = shrinkVertically(tween(MotionBaseMs, easing = PreciseEasing)) +
                            fadeOut(tween(MotionBaseMs)),
                    ) {
                        Text(
                            text = "Looks like a valid key.",
                            style = EarslateTheme.textStyles.bodySmall,
                            color = EarslateTheme.colors.success,
                            modifier = Modifier.semantics {
                                contentDescription = "The pasted key looks valid"
                            },
                        )
                    }
                }

                EmberButton(
                    label = if (hasExistingKey) "Save new key" else "Save key and continue",
                    enabled = keyInput.isNotBlank(),
                    onClick = ::attemptSave,
                )

                ReassuranceLine()

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Shape-checks a candidate key. Returns a human error message, or null when
 * the key looks plausible. Deliberately lenient — Google may lengthen keys —
 * but strict enough to catch pastes of the wrong thing (URLs, emails, blank).
 */
private fun validateKeyShape(key: String): String? = when {
    key.isEmpty() ->
        "Paste your key first — it's the text starting with “AIza” that Google AI Studio showed you."
    key.any { it.isWhitespace() } ->
        "The key has a space or line break in it. Copy it again in one piece — it's a single unbroken block of letters and numbers."
    !key.startsWith("AIza") ->
        "That doesn't look like a Gemini API key. Keys start with “AIza” — make sure you copied the API key itself, not the page address."
    key.length < 30 ->
        "That looks too short to be a full key. Tap the copy button next to the key in Google AI Studio so you get the whole thing."
    !key.all { it.isLetterOrDigit() || it == '-' || it == '_' } ->
        "The key contains characters Google never uses. Copy it again straight from Google AI Studio."
    else -> null
}

@Composable
private fun SavedKeyCard(
    maskedSuffix: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = EarslateTheme.colors.elev1, shape = EarslateTheme.shapes.lg)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "A Gemini API key ending in $maskedSuffix is saved on this device"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "KEY SAVED ON THIS DEVICE",
                style = EarslateTheme.textStyles.meta,
                color = EarslateTheme.colors.success,
            )
            Text(
                text = "••••••••$maskedSuffix",
                style = EarslateTheme.textStyles.body.copy(fontWeight = FontWeight.SemiBold),
                color = EarslateTheme.colors.textPrimary,
            )
        }
        TextButton(
            onClick = onRemove,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
            Text(
                text = "REMOVE",
                style = EarslateTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
                color = EarslateTheme.colors.danger,
            )
        }
    }
}

@Composable
private fun NumberedStep(number: Int, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(24.dp)
                .background(color = EarslateTheme.colors.emberSoft, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = EarslateTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
                color = EarslateTheme.colors.ember,
            )
        }
        Text(
            text = text,
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ReassuranceLine() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = EarslateTheme.colors.surfaceGhost, shape = EarslateTheme.shapes.lg)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = EarslateTheme.colors.ember, shape = CircleShape),
        )
        Text(
            text = "Your key stays on this device. earslate has no account and no " +
                "servers — your audio goes straight from your phone to Google, " +
                "using your key.",
            style = EarslateTheme.textStyles.bodySmall,
            color = EarslateTheme.colors.textSecondary,
        )
    }
}

/**
 * One-shot entrance: fade + small upward slide. With reduced motion the
 * content simply appears.
 */
@Composable
private fun Reveal(content: @Composable () -> Unit) {
    val reducedMotion = rememberReducedMotion()
    if (reducedMotion) {
        content()
        return
    }
    val visibleState = remember {
        MutableTransitionState(initialState = false).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(tween(MotionSlowMs, easing = PreciseEasing)) +
            slideInVertically(
                animationSpec = tween(MotionSlowMs, easing = PreciseEasing),
                initialOffsetY = { it / 16 },
            ),
    ) {
        content()
    }
}
