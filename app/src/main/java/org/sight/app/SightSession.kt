package org.sight.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.sight.app.navigation.NavigationController
import org.sight.app.navigation.WalkingRouteService

/** Retains ephemeral intent without retaining an Activity, camera, microphone, or GPS fix. */
class SightSession(application: Application) : AndroidViewModel(application) {
    val controller = SightController(application)
    val navigation = NavigationController(application, controller::speakProviderText,
        if(BuildConfig.DEBUG) testRouteServiceFactory?.invoke() else null)
    var onRequestStart: (() -> Unit)? = null
    var onRequestStop: (() -> Unit)? = null

    init {
        controller.routeActive = { navigation.guidanceActive }
        navigation.onNavigationCue = controller::speakNavigationCue
        navigation.onNavigationInvalidated = controller::invalidateNavigationSpeech
        controller.onVisualTaskFinished = { if(navigation.guidanceActive) navigation.repeatInstruction() }
        controller.onFreshNavigationCueRequested = { if(navigation.guidanceActive) navigation.repeatInstruction() }
        controller.onClearSavedDestinations = navigation::clearSavedDestinations
        controller.externalCommand = { text ->
            when(text.lowercase()) {
                "start assistance", "start sight" -> { onRequestStart?.invoke(); true }
                "stop assistance", "stop sight", "pause sight" -> {
                    navigation.cancelNavigation()
                    onRequestStop?.invoke() ?: controller.pause()
                    controller.perceptionStatus("Assistance stopped. Camera, microphone and route guidance are paused."); true
                }
                else -> navigation.handleVoiceCommand(text)
            }
        }
    }

    override fun onCleared() {
        onRequestStart=null; onRequestStop=null
        navigation.close()
        controller.close()
        super.onCleared()
    }
    companion object {
        /** Debug instrumentation only; the production default always uses real HTTPS providers. */
        @Volatile internal var testRouteServiceFactory:(()->WalkingRouteService)?=null
    }
}
