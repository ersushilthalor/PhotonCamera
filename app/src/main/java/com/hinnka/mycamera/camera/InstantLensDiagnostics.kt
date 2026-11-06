package com.hinnka.mycamera.camera

import android.os.SystemClock
import android.util.Log

/**
 * Single-tag diagnostic stream for Main <-> Ultra-Wide lens handoff investigations.
 *
 * Every event includes elapsedRealtime so events emitted from the camera, UI, and GL
 * threads can be correlated without relying on wall-clock ordering.
 */
object InstantLensDiagnostics {
    private const val TAG = "InstantLens"

    fun log(event: String, details: String = "") {
        val suffix = if (details.isBlank()) "" else " $details"
        Log.i(TAG, "t=${SystemClock.elapsedRealtime()} event=$event$suffix")
    }
}
