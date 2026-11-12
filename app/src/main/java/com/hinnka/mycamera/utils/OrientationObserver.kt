package com.hinnka.mycamera.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.OrientationEventListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hinnka.mycamera.stabilization.VideoHorizontalLockState
import com.hinnka.mycamera.stabilization.resolveHorizontalLockSensorOrientationDegrees
import kotlin.math.abs

object OrientationObserver {
    private const val TAG = "OrientationObserver"
    private const val FLAT_AXIS_THRESHOLD = 7.5f

    private var sensorManager: SensorManager? = null
    private var orientationListener: OrientationEventListener? = null
    private var gravityListener: SensorEventListener? = null
    private var horizontalLockSensorAvailable = false
    private var isNearFlat by mutableStateOf(false)

    // High-rate floating-point horizon angle. Do not make this Compose state: each sensor
    // sample is consumed by the renderers and should not trigger camera-screen recomposition.
    @Volatile
    var horizontalLockOrientationDegrees: Float = 0f
        private set

    var isLandscape by mutableStateOf(false)
        private set

    var rotationDegrees by mutableStateOf(0f)
        private set

    // Kept for existing UI/material consumers; this is still the rounded platform callback.
    var continuousOrientationDegrees by mutableFloatStateOf(0f)
        private set

    var captureRotationDegrees by mutableStateOf(0f)
        private set

    fun updateOrientation(orientation: Int) {
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return

        continuousOrientationDegrees = orientation.toFloat()

        // Fallback only: the high-rate gravity sensor is preferred for horizontal lock.
        if (!horizontalLockSensorAvailable) {
            horizontalLockOrientationDegrees = orientation.toFloat()
            VideoHorizontalLockState.updateOrientation(
                currentOrientationDegrees = orientation.toFloat(),
                timestampNanos = SystemClock.elapsedRealtimeNanos(),
            )
        }

        if (isNearFlat) return

        when (orientation) {
            in 45..135 -> {
                captureRotationDegrees = 90f
                if (!isLandscape || rotationDegrees != 90f) {
                    isLandscape = true
                    rotationDegrees = 90f
                    PLog.d(TAG, "Orientation locked to landscape-right, orientation=$orientation")
                }
            }
            in 225..315 -> {
                captureRotationDegrees = 270f
                if (!isLandscape || rotationDegrees != 270f) {
                    isLandscape = true
                    rotationDegrees = 270f
                    PLog.d(TAG, "Orientation locked to landscape-left, orientation=$orientation")
                }
            }
            in 135..225 -> {
                captureRotationDegrees = 180f
                if (isLandscape || rotationDegrees != 0f) {
                    isLandscape = false
                    rotationDegrees = 0f
                    PLog.d(TAG, "Orientation locked to reverse-portrait, orientation=$orientation")
                }
            }
            else -> {
                captureRotationDegrees = 0f
                if (isLandscape || rotationDegrees != 0f) {
                    isLandscape = false
                    rotationDegrees = 0f
                    PLog.d(TAG, "Orientation locked to portrait, orientation=$orientation")
                }
            }
        }
    }

    fun observe(context: Context) {
        if (orientationListener != null) return

        sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Prefer Android's fused gravity vector; fall back to the accelerometer on devices
        // that do not expose TYPE_GRAVITY. Both use the same device-coordinate convention.
        val horizontalLockSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (horizontalLockSensor != null) {
            gravityListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    val values = event?.values ?: return
                    if (values.size < 3) return

                    val nearFlat = abs(values[2]) > FLAT_AXIS_THRESHOLD
                    if (nearFlat != isNearFlat) {
                        isNearFlat = nearFlat
                    }

                    val angle = resolveHorizontalLockSensorOrientationDegrees(
                        gravityX = values[0],
                        gravityY = values[1],
                        gravityZ = values[2],
                    ) ?: return

                    horizontalLockOrientationDegrees = angle
                    VideoHorizontalLockState.updateOrientation(
                        currentOrientationDegrees = angle,
                        timestampNanos = event.timestamp,
                    )
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            horizontalLockSensorAvailable = sensorManager?.registerListener(
                gravityListener,
                horizontalLockSensor,
                SensorManager.SENSOR_DELAY_GAME,
            ) == true
        }

        orientationListener = object : OrientationEventListener(
            context.applicationContext,
            SensorManager.SENSOR_DELAY_GAME,
        ) {
            override fun onOrientationChanged(orientation: Int) {
                updateOrientation(orientation)
            }
        }
        orientationListener?.enable()
    }
}
