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
import androidx.core.app.ActivityCompat
import com.classeve.earslate.service.TranslatorTileService
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.classeve.earslate.EarslateRuntime
import com.classeve.earslate.R
import com.classeve.earslate.audio.AudioRoute
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
import com.classeve.earslate.ui.help.HelpScreen
import com.classeve.earslate.security.KeyProvider
import com.classeve.earslate.security.ProviderKeyStore
import com.classeve.earslate.ui.onboarding.ApiKeySetupScreen
import com.classeve.earslate.ui.onboarding.OnboardingScreen
import com.classeve.earslate.ui.settings.SettingsScreen
import com.classeve.earslate.ui.components.ListeningIndicator
import com.classeve.earslate.ui.theme.EarslateTheme
import com.classeve.earslate.ui.theme.MotionBaseMs
import com.classeve.earslate.ui.theme.MotionFastMs
import com.classeve.earslate.ui.theme.PreciseEasing
import com.classeve.earslate.ui.theme.rememberReducedMotion
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
        if (micOk) {
            TranslatorService.start(this)
            return@registerForActivityResult
        }
        // Denial used to fall off the end of this callback: no error, no state
        // change, no toast. RuntimeError.Kind.PERMISSION_DENIED and the
        // "PERMISSION NEEDED" banner both already existed and nothing ever
        // constructed one, so the entire permission-denied experience was dead
        // code and the screen was byte-identical to before the tap.
        //
        // The two cases are not the same and must not read the same. After a
        // first "Don't allow" the user knows what they did. After a permanent
        // denial Android shows no sheet at all, so the tap is indistinguishable
        // from a broken button — and there was no route to Settings anywhere in
        // the app, which left no way back even for someone who knew the cause.
        val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.RECORD_AUDIO,
        )
        EarslateRuntime.stateStore.setError(
            RuntimeError(
                kind = RuntimeError.Kind.PERMISSION_DENIED,
                message = if (canAskAgain) {
                    "earslate needs the microphone to hear the conversation. Tap start to allow it."
                } else {
                    "Microphone access is turned off for earslate. Turn it on in Settings to translate."
                },
            ),
        )
        if (!canAskAgain) micPermissionPermanentlyDenied = true
    }

    /**
     * Set when Android will no longer show the permission sheet, so the UI can
     * offer the only remaining route — the system app-settings page.
     *
     * Recomputed in [onCreate] rather than merely remembered, because an
     * Activity field does not survive a configuration change: rotating the
     * phone reset this to false and turned "OPEN SETTINGS" back into a "RETRY"
     * that provably cannot work. Every input to it outlives the Activity — the
     * error lives in the process-wide state store, and the permission and its
     * rationale flag are platform state — so it is derived, not stored.
     */
    private var micPermissionPermanentlyDenied by mutableStateOf(false)

    private fun recomputeMicDenialState() {
        val denied = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        val alreadyReported =
            EarslateRuntime.stateStore.lastError.value?.kind == RuntimeError.Kind.PERMISSION_DENIED
        micPermissionPermanentlyDenied = denied && alreadyReported &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.RECORD_AUDIO,
            )
    }

    /** Opens this app's page in system Settings, where the mic can be re-enabled. */
    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        recomputeMicDenialState()
        // Warm up the audio device monitor so route state is populated for the UI.
        EarslateRuntime.deviceMonitor(this)

        // Handle REQUEST_START from TranslatorTileService when permission is missing.
        handleRequestStartIntent(intent)

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
                        onOpenAppSettings =
                            if (micPermissionPermanentlyDenied) ::openAppSettings else null,
                    )
                }
            }
        }
    }

    /**
     * The activity is `singleTop`, so when it is already the top of its task the
     * system delivers a re-launch here instead of calling [onCreate] again.
     * Both entry points that bounce the user back for consent or the mic grant
     * (TranslatorService's ACTION_START gate and TranslatorTileService) send the
     * same REQUEST_START intent — without this override those bounces were
     * silently dropped whenever the activity already existed, so tapping the QS
     * tile or the notification's Start action appeared to do nothing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() consistent with what we are about to act on.
        setIntent(intent)
        handleRequestStartIntent(intent)
    }

    private fun handleRequestStartIntent(intent: Intent?) {
        if (intent?.action == ACTION_REQUEST_START) {
            requestStart()
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
        // acknowledge that captured audio is streamed to the selected provider
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
        // Only what the app actually exercises. BLUETOOTH_CONNECT used to be
        // requested here on API 31+ and was never called for — see the note in
        // AndroidManifest.xml.
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
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
     * third parties that can receive audio (Gemini or OpenAI — resolved per
     * session from the keys the user has supplied), the data (microphone audio),
     * the purpose (live translation), and retention, gated behind an explicit
     * "I agree". Shown before the FIRST capture on any entry point; the choice
     * persists.
     *
     * The wording lives in `R.string.audio_disclosure_body`. If the set of
     * providers the app can mint against ever changes, that string and the Play
     * Data safety declaration must change with it.
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

private enum class Screen { ONBOARDING, KEY_SETUP, MAIN, SETTINGS, HELP }

/** Short description of which keys are saved, for the Settings row. */
private fun keySummary(keys: ProviderKeyStore): String {
    val configured = keys.configured()
    return when {
        configured.isEmpty() -> "Not set up"
        configured.size == 1 -> configured.first().displayName
        else -> "${configured.size} providers"
    }
}

@Composable
private fun EarslateApp(
    padding: PaddingValues,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestQsTile: () -> Unit = {},
    onOpenAppSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val settingsRepo = remember(context) { EarslateRuntime.settingsRepository(context) }
    val userSettings by settingsRepo.settings.collectAsState()
    val scope = rememberCoroutineScope()

    val providerKeys = remember(context) { EarslateRuntime.providerKeys(context) }
    // Recomputed on every navigation so adding or removing a key in Settings is
    // reflected immediately, without a restart.
    var hasKey by remember { mutableStateOf(providerKeys.hasAnyKey()) }

    val firstLaunch = remember { !OnboardingPrefs.isCompleted(context) }
    var languageChosen by remember { mutableStateOf(OnboardingPrefs.isLanguageChosen(context)) }
    var everStarted by remember { mutableStateOf(OnboardingPrefs.hasEverStarted(context)) }
    val initialScreen = remember {
        when {
            firstLaunch -> Screen.ONBOARDING
            // Without a key there is nothing the main screen can do, so send
            // the user straight where they can fix that.
            !hasKey -> Screen.KEY_SETUP
            else -> Screen.MAIN
        }
    }
    var screen by rememberSaveable { mutableStateOf(initialScreen) }

    // System back mirrors the on-screen BACK affordances. On MAIN and
    // first-run onboarding the handler is disabled so back exits the app
    // instead of trapping the user on a screen they can't leave. Key setup
    // reached with no key saved is the same case: there is nowhere to go back
    // to that would work.
    BackHandler(
        enabled = screen != Screen.MAIN &&
            // FIRST-RUN onboarding is the trap-free case: there is nothing
            // behind it, so back should exit. Onboarding reached from Settings
            // by "View onboarding" is the opposite, and excluding the whole
            // screen made that a one-way door — system back closed the app
            // instead of going back, from a screen the user had deliberately
            // opened to read.
            !(screen == Screen.ONBOARDING && firstLaunch) &&
            !(screen == Screen.KEY_SETUP && !hasKey),
    ) {
        screen = when (screen) {
            Screen.HELP -> Screen.SETTINGS
            Screen.KEY_SETUP -> Screen.SETTINGS
            Screen.ONBOARDING -> Screen.SETTINGS
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
                alreadySetUp = hasKey,
                languageWasGuessed = !languageChosen,
                onLanguageChange = { lang ->
                    scope.launch { settingsRepo.setMyLanguage(lang.bcp47) }
                    // Pressing Continue past a language you were shown is an
                    // answer, so this marks the question answered either way.
                    // Without it a locale guess and a deliberate choice are the
                    // same stored value, and setup could not tell the user which
                    // one they were looking at.
                    OnboardingPrefs.markLanguageChosen(context)
                    languageChosen = true
                },
                onContinue = {
                    OnboardingPrefs.markCompleted(context)
                    hasKey = providerKeys.hasAnyKey()
                    // A first run always needs a key before the main screen is
                    // of any use.
                    screen = if (hasKey) Screen.MAIN else Screen.KEY_SETUP
                    // The Quick Settings prompt used to fire here, which threw a
                    // system dialog at someone who did not yet have a key and so
                    // could not have used the tile it was offering. It now waits
                    // until setup has actually produced a working app.
                    if (hasKey) onRequestQsTile()
                },
            )
            Screen.KEY_SETUP -> ApiKeySetupScreen(
                padding = padding,
                targetLanguageCode = currentLanguage.bcp47,
                initialProvider = KeyProvider.forProvider(userSettings.provider)
                    ?: KeyProvider.GEMINI,
                onBack = if (hasKey) {
                    { screen = Screen.SETTINGS }
                } else {
                    null
                },
                onDone = {
                    val firstKey = !hasKey
                    hasKey = providerKeys.hasAnyKey()
                    screen = Screen.MAIN
                    // Setup is only genuinely finished here — this is where the
                    // app first becomes able to do anything — so this is where
                    // the tile is worth offering.
                    if (firstKey && hasKey) onRequestQsTile()
                },
                // Removing a key changes whether the app has one at all, and
                // hasKey was only recomputed on navigation — so deleting the
                // last key left the main screen still offering START for a
                // session that could not mint.
                onKeysChanged = { hasKey = providerKeys.hasAnyKey() },
            )
            Screen.MAIN -> MainScreen(
                padding = padding,
                onStart = {
                    OnboardingPrefs.markStarted(context)
                    everStarted = true
                    onStart()
                },
                onStop = onStop,
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenAppSettings = onOpenAppSettings,
                currentLanguage = currentLanguage,
                showFirstRunHint = !everStarted,
                hasKey = hasKey,
                onFinishSetup = { screen = Screen.KEY_SETUP },
            )
            Screen.SETTINGS -> SettingsScreen(
                padding = padding,
                initialMyLanguage = currentLanguage,
                initialTheirLanguage = currentTheirs,
                initialManualLanguages = userSettings.manualLanguages,
                initialExternalOnly = userSettings.externalOnly,
                initialPersistentNotification = userSettings.persistentNotification,
                initialProvider = userSettings.provider,
                onBack = { screen = Screen.MAIN },
                onMyLanguageChange = { lang ->
                    scope.launch { settingsRepo.setMyLanguage(lang.bcp47) }
                    OnboardingPrefs.markLanguageChosen(context)
                    languageChosen = true
                },
                onTheirLanguageChange = { lang ->
                    scope.launch { settingsRepo.setTheirLanguage(lang.bcp47) }
                },
                onManualLanguagesChange = { enabled ->
                    scope.launch { settingsRepo.setManualLanguages(enabled) }
                },
                onExternalOnlyChange = { enabled ->
                    scope.launch { settingsRepo.setExternalOnly(enabled) }
                },
                onPersistentNotificationChange = { enabled ->
                    scope.launch { settingsRepo.setPersistentNotification(enabled) }
                    if (enabled) {
                        NotificationControlService.start(context)
                    } else {
                        NotificationControlService.stop(context)
                    }
                },
                onProviderChange = { provider ->
                    scope.launch { settingsRepo.setProvider(provider) }
                },
                onOpenOnboarding = { screen = Screen.ONBOARDING },
                onOpenHelp = { screen = Screen.HELP },
                onOpenKeySetup = { screen = Screen.KEY_SETUP },
                configuredKeySummary = keySummary(providerKeys),
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
    /** Non-null only when the mic permission can no longer be requested. */
    onOpenAppSettings: (() -> Unit)? = null,
    currentLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    /** Until a session has been started once, say plainly how to start one. */
    showFirstRunHint: Boolean = false,
    /**
     * False means no provider key is stored, so a session cannot open at all.
     * Removing the last key in Settings drops you back here with a START button
     * that can only fail at bootstrap, several seconds later, with a message
     * about the translation service — for a problem that is neither the
     * service's nor a mystery.
     */
    hasKey: Boolean = true,
    onFinishSetup: () -> Unit = {},
) {
    val context = LocalContext.current
    val deviceMonitor = remember(context) { EarslateRuntime.deviceMonitor(context) }

    val state by EarslateRuntime.stateStore.state.collectAsState()
    val route by deviceMonitor.route.collectAsState()
    val captionLines by EarslateRuntime.captionsStore.lines.collectAsState()
    val captionPending by EarslateRuntime.captionsStore.pending.collectAsState()
    val lastError by EarslateRuntime.stateStore.lastError.collectAsState()
    val heard by EarslateRuntime.stateStore.heardLanguage.collectAsState()

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

            // Not a control — a readout. There is nothing to pick here any
            // more, but the user still deserves to know which language the app
            // decided it was hearing, because that decision is what their own
            // speech is being sent back in.
            AnimatedVisibility(
                visible = !hasKey,
                enter = expandVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeIn(tween(MotionBaseMs)),
                exit = shrinkVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeOut(tween(MotionBaseMs)),
            ) {
                MissingKeyCard(onFinishSetup = onFinishSetup)
            }

            AnimatedVisibility(
                visible = hasKey && showFirstRunHint && !state.isActive,
                enter = expandVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeIn(tween(MotionBaseMs)),
                exit = shrinkVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeOut(tween(MotionBaseMs)),
            ) {
                FirstRunHint(language = currentLanguage)
            }

            AnimatedVisibility(
                visible = heard != null,
                enter = expandVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeIn(tween(MotionBaseMs)),
                exit = shrinkVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeOut(tween(MotionBaseMs)),
            ) {
                heard?.let { HeardLanguageRow(theirs = it, mine = currentLanguage) }
            }

            PrimaryButton(
                state = state,
                onStart = onStart,
                onStop = onStop,
                enabled = hasKey,
            )

            AnimatedVisibility(
                visible = lastError != null,
                enter = expandVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeIn(tween(MotionBaseMs)),
                exit = shrinkVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeOut(tween(MotionBaseMs)),
            ) {
                lastError?.let { err ->
                    // When Android will not show the permission sheet again,
                    // RETRY is a button that provably does nothing — the only
                    // route left is the system settings page, so that is what
                    // the banner offers.
                    val settingsRoute = onOpenAppSettings.takeIf {
                        err.kind == RuntimeError.Kind.PERMISSION_DENIED
                    }
                    ErrorBanner(
                        error = err,
                        onRetry = settingsRoute ?: onStart,
                        retryLabel = if (settingsRoute != null) "OPEN SETTINGS" else "RETRY",
                        onDismiss = { EarslateRuntime.stateStore.clearError() },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            CaptionsView(
                lines = captionLines,
                pending = captionPending,
                active = state.isActive,
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TopBar(onOpenSettings: () -> Unit) {
    // Top bar — bgCanvas (matches page), no shadow, no elevation. The settings
    // entry is rendered as the canonical ember profile-capsule pill with a
    // full 48dp touch target and a bounded ripple.
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
                .defaultMinSize(minHeight = 48.dp, minWidth = 48.dp)
                .clip(EarslateTheme.shapes.pill)
                .background(EarslateTheme.colors.ember)
                .clickable(
                    onClick = onOpenSettings,
                    onClickLabel = "Open settings",
                    role = Role.Button,
                )
                .semantics { contentDescription = "Settings" }
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.label_settings),
                style = EarslateTheme.textStyles.meta,
                color = EarslateTheme.colors.onEmber,
            )
        }
    }
}

/**
 * Shown until the first session is started, then never again.
 *
 * Landing on a screen with one button and a status pill leaves the user to
 * guess what happens when they press it — and this app opens a microphone and
 * streams audio, which is not a good thing to be surprised by. Three lines,
 * gone the moment they are no longer news.
 */
@Composable
private fun MissingKeyCard(onFinishSetup: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = EarslateTheme.colors.elev1, shape = EarslateTheme.shapes.lg)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "SETUP UNFINISHED",
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.ember,
        )
        Text(
            text = "earslate runs on your own provider key, and there isn't one saved. " +
                "Adding it takes about a minute.",
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textSecondary,
        )
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .clip(EarslateTheme.shapes.pill)
                .background(EarslateTheme.colors.ember)
                .clickable(
                    onClick = onFinishSetup,
                    onClickLabel = "Add a provider key",
                    role = Role.Button,
                )
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = "ADD A KEY",
                style = EarslateTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
                color = EarslateTheme.colors.onEmber,
            )
        }
    }
}

@Composable
private fun FirstRunHint(language: TargetLanguage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = EarslateTheme.colors.elev1, shape = EarslateTheme.shapes.lg)
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "FIRST TIME",
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.ember,
        )
        Text(
            text = "Earbuds in, then tap START. Everything spoken around you arrives in " +
                "${language.displayName}, and what you say goes back out in whatever they " +
                "were last speaking.",
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun HeardLanguageRow(theirs: TargetLanguage, mine: TargetLanguage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = EarslateTheme.colors.elev1, shape = EarslateTheme.shapes.lg)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Hearing ${theirs.displayName}, translating to ${mine.displayName}"
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "HEARING",
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.textTertiary,
        )
        Text(
            text = theirs.displayName,
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textPrimary,
        )
        Icon(
            imageVector = Icons.Rounded.SwapHoriz,
            contentDescription = null,
            tint = EarslateTheme.colors.ember,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = mine.displayName,
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
    enabled: Boolean = true,
) {
    val isActive = state.isActive
    val labelRes = if (isActive) R.string.action_stop else R.string.action_start
    val label = stringResource(labelRes)
    val reducedMotion = rememberReducedMotion()

    // Ember block — primary action. onEmber text, brand md radius.
    // STOP variant uses bg-elev-2 + cream so the user gets a strong visual
    // signal that this is a destructive / "leave the active state" action.
    // Colors cross-fade between the two states; a light press-scale gives
    // tactile feedback (both skipped under "remove animations").
    val container by animateColorAsState(
        targetValue = if (isActive) EarslateTheme.colors.elev2 else EarslateTheme.colors.ember,
        animationSpec = tween(MotionBaseMs, easing = PreciseEasing),
        label = "primary-bg",
    )
    val content by animateColorAsState(
        targetValue = if (isActive) EarslateTheme.colors.cream else EarslateTheme.colors.onEmber,
        animationSpec = tween(MotionBaseMs, easing = PreciseEasing),
        label = "primary-fg",
    )

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.97f else 1f,
        animationSpec = tween(MotionFastMs, easing = PreciseEasing),
        label = "primary-scale",
    )

    Button(
        onClick = { if (isActive) onStop() else onStart() },
        enabled = enabled || isActive,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
        shape = EarslateTheme.shapes.pill,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics {
                contentDescription = if (isActive) "Stop translating" else "Start listening and translating"
            },
    ) {
        if (isActive) {
            ListeningIndicator(color = content)
            Spacer(Modifier.size(10.dp))
        }
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

    // While a session is live the dot breathes gently; static under the
    // system "remove animations" setting (and when idle).
    val reducedMotion = rememberReducedMotion()
    val dotAlpha: Float = if (active && !reducedMotion) {
        rememberInfiniteTransition(label = "status-dot").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = PreciseEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "status-dot-alpha",
        ).value
    } else {
        1f
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .background(color = bg, shape = EarslateTheme.shapes.pill)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Translator status: $label"
            },
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(dotAlpha)
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
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Audio output: ${label.lowercase()}"
            },
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
