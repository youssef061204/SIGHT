package org.sight.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.*

data class CameraPose(val capturedAtMs:Long, val rearCameraPitchDegrees:Float, val magneticHeadingDegrees:Float?, val accuracy:Int) {
    fun description(nowMs:Long):String {
        if(nowMs-capturedAtMs !in 0..2_000) return "Camera orientation is unavailable. Image directions do not establish your walking direction."
        val angle=when { rearCameraPitchDegrees>55 -> "The rear camera is aimed mostly upward."
            rearCameraPitchDegrees < -55 -> "The rear camera is aimed mostly downward."
            else -> "The rear camera is approximately level." }
        return "$angle Compass ${if(magneticHeadingDegrees==null) "heading is unreliable" else "reports magnetic camera heading ${magneticHeadingDegrees.roundToInt()} degrees"}. This does not establish body or walking direction."
    }
}

/** Optical axis of a rear camera is device -Z. No body-direction or walking-lane inference. */
class CameraOrientation(context:Context, private val onPose:(CameraPose)->Unit):SensorEventListener, AutoCloseable {
    private val manager=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor=manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var accuracy=SensorManager.SENSOR_STATUS_UNRELIABLE
    private var running=false
    fun start() { if(!running && sensor!=null) running=manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_NORMAL) }
    override fun onAccuracyChanged(sensor:Sensor?, value:Int) { accuracy=value }
    override fun onSensorChanged(event:SensorEvent) {
        if(!running) return
        val matrix=FloatArray(9); SensorManager.getRotationMatrixFromVector(matrix,event.values)
        val east=-matrix[2]; val north=-matrix[5]; val up=-matrix[8]
        val horizontal=hypot(east,north)
        val heading=if(accuracy>=SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM && horizontal>.35f)
            ((Math.toDegrees(atan2(east.toDouble(),north.toDouble()))+360)%360).toFloat() else null
        onPose(CameraPose(event.timestamp/1_000_000,Math.toDegrees(asin(up.coerceIn(-1f,1f)).toDouble()).toFloat(),heading,accuracy))
    }
    fun stop(){ manager.unregisterListener(this); running=false }
    override fun close()=stop()
}
