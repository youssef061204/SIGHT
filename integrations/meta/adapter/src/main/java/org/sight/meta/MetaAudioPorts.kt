package org.sight.meta

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.StateFlow

enum class MetaAudioAvailability { NOT_CONFIGURED, PERMISSION_REQUIRED, READY, ACTIVE, UNAVAILABLE }
data class MetaAudioState(val availability: MetaAudioAvailability, val detail: String)

/** Optional host-owned input. Camera permission alone never authorizes the wearable microphone. */
interface MetaAudioInputPort {
    val state: StateFlow<MetaAudioState>
    suspend fun start()
    suspend fun stop()
}

/** Host's existing TTS/priority policy can implement this; a route name is not proof of audibility. */
interface MetaAudioOutputPort {
    val state: StateFlow<MetaAudioState>
    fun speak(text: String, interrupt: Boolean): Boolean
    fun stop()
}

data class MetaAudioRoute(val id: Int, val name: String, val input: Boolean, val output: Boolean, val bluetooth: Boolean)

/** Reports devices Android exposes. It does not select a route or claim that speech was heard. */
class MetaAudioRouteProbe(context: Context) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    fun availableRoutes(): List<MetaAudioRoute> = audio.getDevices(AudioManager.GET_DEVICES_ALL).map { device ->
        MetaAudioRoute(device.id, device.productName.toString(), device.isSource, device.isSink,
            device.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER))
    }
}
