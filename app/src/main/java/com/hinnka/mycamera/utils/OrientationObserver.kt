package com.hinnka.mycamera.utils

import android.content.Context
import android.hardware.SensorManager
import android.view.OrientationEventListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object OrientationObserver {
    private const val TAG = "OrientationObserver"

    private var orientationListener: OrientationEventListener? = null

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
