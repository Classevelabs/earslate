package com.classeve.earslate.update

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.classeve.earslate.BuildConfig
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the screen shows about an update from Google Play. */
enum class PlayGate { None, Required, Ready }

/** What a copy does with what Google Play says about the newest release. */
internal enum class PlayStep { Nothing, Require, Offer, Restart }

/** A release uploaded at this priority or higher must be installed before earslate opens. */
internal const val REQUIRED_PRIORITY = 4

/** At this priority or higher it is offered, and the person may say no. */
internal const val OFFERED_PRIORITY = 1

/**
 * The rule, apart from Play so it can be tested. The priority is the one set
 * when the release was uploaded; Play reports the highest of every release
 * newer than this copy.
 */
internal fun playStepFor(
    availability: Int,
    installStatus: Int,
    priority: Int,
    immediateAllowed: Boolean,
    flexibleAllowed: Boolean,
): PlayStep = when {
    installStatus == InstallStatus.DOWNLOADED -> PlayStep.Restart
    availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
        if (priority >= REQUIRED_PRIORITY && immediateAllowed) PlayStep.Require else PlayStep.Nothing
    availability != UpdateAvailability.UPDATE_AVAILABLE -> PlayStep.Nothing
    priority >= REQUIRED_PRIORITY && immediateAllowed -> PlayStep.Require
    priority >= OFFERED_PRIORITY && flexibleAllowed -> PlayStep.Offer
    else -> PlayStep.Nothing
}

private fun AppUpdateInfo.step(): PlayStep = playStepFor(
    availability = updateAvailability(),
    installStatus = installStatus(),
    priority = updatePriority(),
    immediateAllowed = isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
    flexibleAllowed = isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
)

/**
 * Google Play's in-app updates for a copy installed from Play. The Play Store
 * app asks and installs; earslate itself sends nothing for it.
 */
class PlayUpdates(
    activity: ComponentActivity,
    managerFor: (ComponentActivity) -> AppUpdateManager = AppUpdateManagerFactory::create,
) {
    // Made at the first lifecycle event: a field initializer runs before the
    // activity has a context, and the factory needs one.
    private val manager by lazy { managerFor(activity) }

    private val gateState = MutableStateFlow(PlayGate.None)
    val gate: StateFlow<PlayGate> = gateState.asStateFlow()

    // Per run: a required update the person backed out of is not started again by
    // itself (the gate's button does that), one Play could not finish lets them in
    // until the next start, and an offer is made once.
    private var declined = false
    private var failed = false
    private var offered = false
    private var requiring = false

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (!requiring) return@registerForActivityResult
            requiring = false
            when (result.resultCode) {
                Activity.RESULT_OK -> Unit
                Activity.RESULT_CANCELED -> {
                    declined = true
                    gateState.value = PlayGate.Required
                }
                else -> {
                    failed = true
                    gateState.value = PlayGate.None
                }
            }
        }

    private val listener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) gateState.value = PlayGate.Ready
    }

    init {
        // A copy from classeve.com is signed with a key Play cannot update; SiteUpdates serves it.
        if (!BuildConfig.FROM_SITE) {
            activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) = manager.registerListener(listener)
                override fun onResume(owner: LifecycleOwner) = check()
                override fun onDestroy(owner: LifecycleOwner) = manager.unregisterListener(listener)
            })
        }
    }

    private fun check() {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            when (info.step()) {
                PlayStep.Require -> when {
                    failed -> gateState.value = PlayGate.None
                    declined -> gateState.value = PlayGate.Required
                    else -> start(info, AppUpdateType.IMMEDIATE)
                }
                PlayStep.Offer -> if (!offered) {
                    offered = true
                    start(info, AppUpdateType.FLEXIBLE)
                }
                PlayStep.Restart -> gateState.value = PlayGate.Ready
                PlayStep.Nothing -> if (gateState.value == PlayGate.Required) gateState.value = PlayGate.None
            }
        }
    }

    /** The required gate's button: Play's update screen again. */
    fun update() {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.step() == PlayStep.Require) start(info, AppUpdateType.IMMEDIATE) else gateState.value = PlayGate.None
        }
    }

    /** Installs an update downloaded in the background; Play restarts earslate. */
    fun restart() {
        manager.completeUpdate()
    }

    fun later() {
        gateState.value = PlayGate.None
    }

    private fun start(info: AppUpdateInfo, type: Int) {
        requiring = type == AppUpdateType.IMMEDIATE
        runCatching {
            manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(type).build())
        }.onFailure { requiring = false }
    }
}
