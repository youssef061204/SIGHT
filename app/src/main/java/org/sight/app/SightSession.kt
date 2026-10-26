package org.sight.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.sight.app.navigation.NavigationController

/** Retains ephemeral intent without retaining an Activity, camera, microphone, or GPS fix. */
class SightSession(application: Application) : AndroidViewModel(application) {
    val controller = SightController(application)
    val navigation = NavigationController(application, controller::speakProviderText)

    init { controller.externalCommand = navigation::handleVoiceCommand }

    override fun onCleared() {
        navigation.close()
        controller.close()
        super.onCleared()
    }
}
