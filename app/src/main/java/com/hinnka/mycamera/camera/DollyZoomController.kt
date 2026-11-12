package com.hinnka.mycamera.camera

import kotlin.math.abs
import kotlin.math.exp

/** Frame-driven Dolly Zoom controller. Feed measured subject height and the zoom actually applied. */
class DollyZoomController {
    enum class State { OFF, ARMED, LOCKED }
    data class Update(val zoom: Float, val subjectLost: Boolean = false, val rangeLimited: Boolean = false)

    var state: State = State.OFF
        private set
    private var startZoom = 1f
    private var startDistance = 1f
    private var filteredDistance = 1f
    private var previousDistance = 1f
    private var velocity = 0f
    private var previousTimestampNs = 0L
    private var lastCommand = 1f
    private var filteredCommand = 1f
    private var lastSeenNs = 0L

    fun arm(currentZoom: Float) {
        startZoom = currentZoom.coerceAtLeast(0.01f)
        lastCommand = startZoom
        filteredCommand = startZoom
        state = State.ARMED
    }

    fun lock(subjectHeight: Float, appliedZoom: Float, timestampNs: Long): Boolean {
        if (state != State.ARMED || !subjectHeight.isFinite() || subjectHeight <= 0f) return false
        startZoom = appliedZoom.coerceAtLeast(0.01f)
        startDistance = startZoom / subjectHeight
        filteredDistance = startDistance
        previousDistance = startDistance
        previousTimestampNs = timestampNs
        lastSeenNs = timestampNs
        lastCommand = startZoom
        filteredCommand = startZoom
        state = State.LOCKED
        return true
    }

    fun update(subjectHeight: Float?, appliedZoom: Float, timestampNs: Long, minZoom: Float, maxZoom: Float): Update? {
        if (state != State.LOCKED) return null
        if (subjectHeight == null || !subjectHeight.isFinite() || subjectHeight <= 0f) {
            return Update(lastCommand, subjectLost = true)
        }
        val distance = appliedZoom.coerceAtLeast(0.01f) / subjectHeight
        if (!distance.isFinite() || distance <= 0f) return Update(lastCommand, subjectLost = true)
        val dt = ((timestampNs - previousTimestampNs).coerceAtLeast(1L) / 1_000_000_000f).coerceIn(1f / 120f, 0.2f)
        val rawVelocity = (distance - previousDistance) / dt
        velocity += 0.25f * (rawVelocity - velocity)
        val cutoffHz = 1.2f + 0.8f * abs(velocity)
        val alpha = 1f - exp((-2f * Math.PI.toFloat() * cutoffHz * dt).toDouble()).toFloat()
        filteredDistance += alpha * (distance - filteredDistance)
        val predictedDistance = (filteredDistance + velocity * dt * 2.5f).coerceAtLeast(0.001f)
        val target = startZoom * (predictedDistance / startDistance)
        val bounded = target.coerceIn(minZoom, maxZoom)
        val delta = bounded - lastCommand
        val deadband = maxOf(0.01f, lastCommand * 0.0125f)
        val rateLimited = if (abs(delta) < deadband) lastCommand else
            lastCommand + delta.coerceIn(-lastCommand * 0.025f, lastCommand * 0.025f)
        filteredCommand += 0.35f * (rateLimited - filteredCommand)
        lastCommand = filteredCommand.coerceIn(minZoom, maxZoom)
        previousDistance = distance
        previousTimestampNs = timestampNs
        lastSeenNs = timestampNs
        return Update(lastCommand, rangeLimited = target < minZoom || target > maxZoom)
    }

    fun isLost(timestampNs: Long): Boolean = state == State.LOCKED && lastSeenNs > 0L && timestampNs - lastSeenNs > 350_000_000L
    fun stop(): Float { val restoreZoom = startZoom; state = State.OFF; return restoreZoom }
}
