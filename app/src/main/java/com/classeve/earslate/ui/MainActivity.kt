package com.classeve.earslate.ui

import android.Manifest
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.classeve.earslate.service.TranslatorTileService
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.classeve.earslate.EarslateRuntime
import com.classeve.earslate.R
import com.classeve.earslate.audio.AudioRoute
import com.classeve.earslate.auth.AuthStore
import com.classeve.earslate.service.TranslatorService
import com.classeve.earslate.session.RuntimeError
import com.classeve.earslate.session.RuntimeState
import com.classeve.earslate.session.SupportedLanguages
import com.classeve.earslate.session.TargetLanguage
import com.classeve.earslate.session.isActive
import com.classeve.earslate.settings.OnboardingPrefs
import com.classeve.earslate.settings.SettingsRepository
import com.classeve.earslate.ui.captions.CaptionsView
import com.classeve.earslate.ui.components.ErrorBanner
import com.classeve.earslate.ui.diagnostics.DiagnosticsScreen
import com.classeve.earslate.ui.help.HelpScreen
import com.classeve.earslate.ui.onboarding.OnboardingScreen
import com.classeve.earslate.ui.onboarding.SignInScreen
import com.classeve.earslate.ui.settings.LanguagePickerDialog
import com.classeve.earslate.ui.settings.SettingsScreen
import com.classeve.earslate.ui.theme.EarslateTheme
import com.classeve.earslate.ui.theme.MotionBaseMs
import com.classeve.earslate.ui.theme.PreciseEasing
import com.classeve.earslate.service.NotificationControlService
import com.classeve.earslate.service.NotificationFactory
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_REQUEST_START = "com.classeve.earslate.action.REQUEST_START"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micOk) TranslatorService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Warm up the audio device monitor so route state is populated for the UI.
        EarslateRuntime.deviceMonitor(this)

        // Handle REQUEST_START from TranslatorTileService when permission is missing.
        if (intent?.action == ACTION_REQUEST_START) {
            requestStart()
        }

        // Start the persistent notification control service if the user has opted in.
        if (EarslateRuntime.settingsRepository(this).settings.value.persistentNotification) {
            NotificationControlService.start(this)
        }

        setContent {
            EarslateTheme {
                Scaffold(
                    containerColor = EarslateTheme.colors.canvas,
                    contentColor = EarslateTheme.colors.textPrimary,
                ) { inner ->
                    EarslateApp(
                        padding = inner,
                        onStart = ::requestStart,
                        onStop = { TranslatorService.stop(this) },
                        onRequestQsTile = ::requestAddQuickSettingsTile,
                    )
                }
            }
        }
    }

    /**
     * On API 33+, prompts the user with a native system dialog to add the
     * earslate Quick Settings tile. Called once after onboarding completes.
     */
    fun requestAddQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = getSystemService(StatusBarManager::class.java) ?: return
            sbm.requestAddTileService(
                ComponentName(this, TranslatorTileService::class.java),
                getString(R.string.app_name),
                Icon.createWithResource(this, R.drawable.ic_notification),
                { /* executor */ it.run() },
                { resultCode ->
                    Log.i("MainActivity", "QS tile request result: $resultCode")
                },
            )
        }
    }

    private fun requestStart() {
        // Play Prominent Disclosure & Consent: the user must affirmatively
        // acknowledge that captured audio is streamed to Google's Gemini
        // service for translation BEFORE the first microphone capture. This
        // is the in-app path (start button, REQUEST_START intent) and it's
        // where the disclosure dialog is actually shown, since only an
        // Activity can present it. It is NOT the only gate: the QS tile and
        // the idle notification's "Start" action can both reach
        // TranslatorService directly without passing through here, so the
        // authoritative check lives in TranslatorService.onStartCommand()
        // (ACTION_START) — it bounces back to this activity via the same
        // REQUEST_START intent if consent hasn't been accepted yet. This
        // check here is what lets the button flow show the dialog inline
        // instead of round-tripping through the service first. Once accepted,
        // this is a no-op.
        if (!OnboardingPrefs.isAudioDisclosureAccepted(this)) {
            showAudioEgressDisclosure()
            return
        }
        proceedStart()
    }

    private fun proceedStart() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            TranslatorService.start(this)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * Prominent in-app disclosure (Google Play User Data policy): names the
     * third party (Google Gemini), the data (microphone audio), the purpose
     * (live translation), and retention, gated behind an explicit "I agree".
     * Shown before the FIRST capture on any entry point; the choice persists.
     */
    private fun showAudioEgressDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.audio_disclosure_title))
            .setMessage(getString(R.string.audio_disclosure_body))
            .setCancelable(true)
            .setPositiveButton(getString(R.string.audio_disclosure_agree)) { _, _ ->
                OnboardingPrefs.markAudioDisclosureAccepted(this)
                proceedStart()
            }
            .setNegativeButton(getString(R.string.audio_disclosure_decline), null)
            .setNeutralButton(getString(R.string.audio_disclosure_privacy)) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://classeve.com/privacy"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .show()
    }
}

private enum class Screen { ONBOARDING, SIGN_IN, MAIN, SETTINGS, DIAGNOSTICS, HELP }

@Composable
private fun EarslateApp(
    padding: PaddingValues,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestQsTile: () -> Unit = {},
) {
    val context = LocalContext.current
    val settingsRepo = remember(context) { EarslateRuntime.settingsRepository(context) }
    val userSettings by settingsRepo.settings.collectAsState()
    val scope = rememberCoroutineScope()

    val firstLaunch = remember { !OnboardingPrefs.isCompleted(context) }
    val initialScreen = remember {
        when {
            firstLaunch -> Screen.ONBOARDING
            // Onboarding done but no stored session → require sign-in before
            // we let the user near the start button. Once paired, the worker's
            // bootstrap call is the one source of truth for plan + entitlement;
            // this gate just keeps the user from hitting an inevitable 401.
            AuthStore.load(context) == null -> Screen.SIGN_IN
            else -> Screen.MAIN
        }
    }
    var screen by rememberSaveable { mutableStateOf(initialScreen) }

    // If the bootstrap layer determines the stored session is no longer
    // valid (refresh rejected, worker said 401, heartbeat 401), it sets a
    // typed RuntimeError. Watch for it here and bounce back to sign-in so
    // the app can never be silently broken. The stored tokens are NOT
    // cleared — once paired, only manual sign-out or a successful re-pair
    // replaces them (a spurious server 401 must never permanently un-pair
    // the device). Re-pairing on the sign-in screen overwrites the session.
    val lastError by EarslateRuntime.stateStore.lastError.collectAsState()
    LaunchedEffect(lastError) {
        if (lastError?.kind == RuntimeError.Kind.AUTH_REQUIRED && screen != Screen.SIGN_IN) {
            screen = Screen.SIGN_IN
            EarslateRuntime.stateStore.clearError()
        }
    }

    BackHandler(
        enabled = screen != Screen.MAIN && screen != Screen.ONBOARDING && screen != Screen.SIGN_IN,
    ) {
        screen = when (screen) {
            Screen.DIAGNOSTICS -> Screen.SETTINGS
            Screen.HELP -> Screen.SETTINGS
            else -> Screen.MAIN
        }
    }

    // Resolve persisted BCP-47 tags to TargetLanguage objects.
    val currentLanguage = remember(userSettings.myLanguageBcp47) {
        SupportedLanguages.firstOrNull { it.bcp47 == userSettings.myLanguageBcp47 }
            ?: TargetLanguage.EnglishUS
    }
    val currentTheirs = remember(userSettings.theirLanguageBcp47) {
        SupportedLanguages.firstOrNull { it.bcp47 == userSettings.theirLanguageBcp47 }
            ?: TargetLanguage.EnglishUS
    }

    // On first launch, pre-select the device locale so the onboarding picker
    // starts with a sensible default instead of always showing English.
    LaunchedEffect(firstLaunch) {
        if (firstLaunch) settingsRepo.initializeFromLocaleIfNeeded()
    }

    Crossfade(
        targetState = screen,
        animationSpec = tween(durationMillis = MotionBaseMs, easing = PreciseEasing),
        label = "earslate-nav",
    ) { current ->
        when (current) {
            Screen.ONBOARDING -> OnboardingScreen(
                padding = padding,
                initialLanguage = currentLanguage,
                onLanguageChange = { lang ->
                    scope.launch { settingsRepo.setMyLanguage(lang.bcp47) }
                },
                onContinue = {
                    OnboardingPrefs.markCompleted(context)
                    // Onboarding only marks the rationale-tour seen. The user
                    // still needs a paired ClassEve session before bootstrap
                    // can hit the worker. Send them through sign-in if they
                    // don't already have one.
                    screen = if (AuthStore.load(context) == null) {
                        Screen.SIGN_IN
                    } else {
                        Screen.MAIN
                    }
                    onRequestQsTile()
                },
            )
            Screen.SIGN_IN -> SignInScreen(
                padding = padding,
                onSignedIn = { screen = Screen.MAIN },
            )
            Screen.MAIN -> MainScreen(
                padding = padding,
                onStart = onStart,
                onStop = onStop,
                onOpenSettings = { screen = Screen.SETTINGS },
                currentLanguage = currentLanguage,
                currentTheirLanguage = currentTheirs,
                onMyLanguageChange = { lang ->
                    scope.launch { settingsRepo.setMyLanguage(lang.bcp47) }
                },
                onTheirLanguageChange = { lang ->
                    scope.launch { settingsRepo.setTheirLanguage(lang.bcp47) }
                },
            )
            Screen.SETTINGS -> SettingsScreen(
                padding = padding,
                initialMyLanguage = currentLanguage,
                initialTheirLanguage = currentTheirs,
                initialCaptionsEnabled = userSettings.captionsEnabled,
                initialPreferEarbuds = userSettings.preferEarbuds,
                initialDiagnosticsEnabled = userSettings.diagnosticsEnabled,
                initialPersistentNotification = userSettings.persistentNotification,
                onBack = { screen = Screen.MAIN },
                onMyLanguageChange = { lang ->
                    scope.launch { settingsRepo.setMyLanguage(lang.bcp47) }
                },
                onTheirLanguageChange = { lang ->
                    scope.launch { settingsRepo.setTheirLanguage(lang.bcp47) }
                },
                onCaptionsEnabledChange = { enabled ->
                    scope.launch { settingsRepo.setCaptionsEnabled(enabled) }
                },
                onPreferEarbudsChange = { enabled ->
                    scope.launch { settingsRepo.setPreferEarbuds(enabled) }
                },
                onDiagnosticsEnabledChange = { enabled ->
                    scope.launch { settingsRepo.setDiagnosticsEnabled(enabled) }
                },
                onPersistentNotificationChange = { enabled ->
                    scope.launch { settingsRepo.setPersistentNotification(enabled) }
                    if (enabled) {
                        NotificationControlService.start(context)
                    } else {
                        NotificationControlService.stop(context)
                    }
                },
                onOpenDiagnostics = { screen = Screen.DIAGNOSTICS },
                onOpenOnboarding = { screen = Screen.ONBOARDING },
                onOpenHelp = { screen = Screen.HELP },
            )
            Screen.DIAGNOSTICS -> DiagnosticsScreen(
                padding = padding,
                onBack = { screen = Screen.SETTINGS },
            )
            Screen.HELP -> HelpScreen(
                padding = padding,
                onBack = { screen = Screen.SETTINGS },
            )
        }
    }
}

@Composable
private fun MainScreen(
    padding: PaddingValues,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    currentLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    currentTheirLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    onMyLanguageChange: (TargetLanguage) -> Unit = {},
    onTheirLanguageChange: (TargetLanguage) -> Unit = {},
) {
    val context = LocalContext.current
    val deviceMonitor = remember(context) { EarslateRuntime.deviceMonitor(context) }

    val state by EarslateRuntime.stateStore.state.collectAsState()
    val route by deviceMonitor.route.collectAsState()
    val captionLines by EarslateRuntime.captionsStore.lines.collectAsState()
    val captionPending by EarslateRuntime.captionsStore.pending.collectAsState()
    val lastError by EarslateRuntime.stateStore.lastError.collectAsState()

    var showMyPicker by remember { mutableStateOf(false) }
    var showTheirPicker by remember { mutableStateOf(false) }
    if (showMyPicker) {
        LanguagePickerDialog(
            currentLanguage = currentLanguage,
            onSelect = { onMyLanguageChange(it); showMyPicker = false },
            onDismiss = { showMyPicker = false },
        )
    }
    if (showTheirPicker) {
        LanguagePickerDialog(
            currentLanguage = currentTheirLanguage,
            onSelect = { onTheirLanguageChange(it); showTheirPicker = false },
            onDismiss = { showTheirPicker = false },
        )
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
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            TopBar(onOpenSettings = onOpenSettings)

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.hero_headline),
                style = EarslateTheme.textStyles.display,
                color = EarslateTheme.colors.textPrimary,
            )

            Text(
                text = stringResource(R.string.hero_body, currentLanguage.displayName),
                style = EarslateTheme.textStyles.body,
                color = EarslateTheme.colors.textSecondary,
            )

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(state = state)
                RoutePill(route = route)
            }

            AnimatedVisibility(
                visible = route == AudioRoute.SPEAKER,
                enter = expandVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeIn(tween(MotionBaseMs)),
                exit = shrinkVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeOut(tween(MotionBaseMs)),
            ) {
                SpeakerEchoNotice()
            }

            LanguageBar(
                mine = currentLanguage,
                theirs = currentTheirLanguage,
                onPickMine = { showMyPicker = true },
                onPickTheirs = { showTheirPicker = true },
            )

            PrimaryButton(
                state = state,
                onStart = onStart,
                onStop = onStop,
            )

            AnimatedVisibility(
                visible = lastError != null,
                enter = expandVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeIn(tween(MotionBaseMs)),
                exit = shrinkVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeOut(tween(MotionBaseMs)),
            ) {
                lastError?.let { err ->
                    val onViewPlans = if (err.kind == RuntimeError.Kind.SUBSCRIPTION_REQUIRED) {
                        {
                            // Custom Tabs would be nicer; we don't yet bundle
                            // androidx.browser. ACTION_VIEW is acceptable for v0.
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://classeve.com/releases/earslate/pricing"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            Unit
                        }
                    } else {
                        null
                    }
                    ErrorBanner(
                        error = err,
                        onRetry = onStart,
                        onDismiss = { EarslateRuntime.stateStore.clearError() },
                        onViewPlans = onViewPlans,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            CaptionsView(
                lines = captionLines,
                pending = captionPending,
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TopBar(onOpenSettings: () -> Unit) {
    // Top bar — bgCanvas (matches page), no shadow, no elevation. The settings
    // entry is rendered as the canonical ember profile-capsule pill.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(EarslateTheme.colors.canvas),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name).uppercase(),
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.textTertiary,
        )
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clickable(
                    onClick = onOpenSettings,
                    onClickLabel = "Open settings",
                )
                .semantics { contentDescription = "Settings" }
                .background(
                    color = EarslateTheme.colors.ember,
                    shape = EarslateTheme.shapes.pill,
                )
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.label_settings),
                style = EarslateTheme.textStyles.meta,
                color = EarslateTheme.colors.onEmber,
            )
        }
    }
}

@Composable
private fun LanguageBar(
    mine: TargetLanguage,
    theirs: TargetLanguage,
    onPickMine: () -> Unit,
    onPickTheirs: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LangChip(role = "YOU", lang = mine, modifier = Modifier.weight(1f), onClick = onPickMine)
        Text(
            text = "⇄",
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textTertiary,
        )
        LangChip(role = "THEM", lang = theirs, modifier = Modifier.weight(1f), onClick = onPickTheirs)
    }
}

@Composable
private fun LangChip(
    role: String,
    lang: TargetLanguage,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .background(color = EarslateTheme.colors.elev1, shape = EarslateTheme.shapes.lg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = role,
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.textTertiary,
        )
        Text(
            text = lang.displayName,
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun PrimaryButton(
    state: RuntimeState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val isActive = state.isActive
    val labelRes = if (isActive) R.string.action_stop else R.string.action_start
    val label = stringResource(labelRes)

    // Ember block — primary action. onEmber text, brand md radius.
    // STOP variant uses bg-elev-2 + cream so the user gets a strong visual
    // signal that this is a destructive / "leave the active state" action.
    val container = if (isActive) EarslateTheme.colors.elev2 else EarslateTheme.colors.ember
    val content = if (isActive) EarslateTheme.colors.cream else EarslateTheme.colors.onEmber

    Button(
        onClick = { if (isActive) onStop() else onStart() },
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
        shape = EarslateTheme.shapes.pill,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
    ) {
        Text(
            text = label.uppercase(),
            style = EarslateTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun StatusPill(state: RuntimeState) {
    val label = stringResource(statusLabelFor(state))
    // Tag-chip palette per brand: idle = surfaceSoft + creamSoft text;
    // active = ember + onEmber text; warning/degraded keep amber/danger ramps
    // while staying inside the brand cream/ember family.
    val active = when (state) {
        RuntimeState.LISTENING, RuntimeState.PLAYING, RuntimeState.READY -> true
        else -> false
    }
    val targetBg = when {
        active -> EarslateTheme.colors.ember
        state == RuntimeState.DEGRADED -> EarslateTheme.colors.oxbloodSoft
        else -> EarslateTheme.colors.surfaceSoft
    }
    val targetFg = when {
        active -> EarslateTheme.colors.onEmber
        state == RuntimeState.RECONNECTING || state == RuntimeState.RESUMING -> EarslateTheme.colors.warning
        state == RuntimeState.DEGRADED -> EarslateTheme.colors.cream
        else -> EarslateTheme.colors.creamSoft
    }
    val bg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(MotionBaseMs, easing = PreciseEasing),
        label = "status-bg",
    )
    val fg by animateColorAsState(
        targetValue = targetFg,
        animationSpec = tween(MotionBaseMs, easing = PreciseEasing),
        label = "status-fg",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .background(color = bg, shape = EarslateTheme.shapes.pill)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = fg, shape = CircleShape),
        )
        Text(
            text = label.uppercase(),
            style = EarslateTheme.textStyles.meta,
            color = fg,
        )
    }
}

@Composable
private fun RoutePill(route: AudioRoute) {
    val label = stringResource(
        when (route) {
            AudioRoute.BLUETOOTH -> R.string.route_bluetooth
            AudioRoute.WIRED -> R.string.route_wired
            AudioRoute.SPEAKER -> R.string.route_speaker
            AudioRoute.UNKNOWN -> R.string.route_unknown
        },
    )
    // Route tag-chip — surfaceSoft fill, creamSoft mono uppercase.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                color = EarslateTheme.colors.surfaceSoft,
                shape = EarslateTheme.shapes.pill,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.creamSoft,
        )
    }
}

@Composable
private fun SpeakerEchoNotice() {
    // Speaker-echo notice — flat bg-elev-1 plate, no border.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = EarslateTheme.colors.elev1,
                shape = EarslateTheme.shapes.lg,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = EarslateTheme.colors.ember, shape = CircleShape),
        )
        Text(
            text = stringResource(R.string.route_speaker_echo_warning),
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textSecondary,
        )
    }
}

private fun statusLabelFor(state: RuntimeState): Int = when (state) {
    RuntimeState.IDLE -> R.string.status_idle
    RuntimeState.BOOTSTRAPPING -> R.string.status_bootstrapping
    RuntimeState.CONNECTING -> R.string.status_connecting
    RuntimeState.READY -> R.string.status_ready
    RuntimeState.LISTENING -> R.string.status_listening
    RuntimeState.PLAYING -> R.string.status_playing
    RuntimeState.RECONNECTING -> R.string.status_reconnecting
    RuntimeState.RESUMING -> R.string.status_resuming
    RuntimeState.DEGRADED -> R.string.status_degraded
    RuntimeState.STOPPING -> R.string.status_stopping
}
