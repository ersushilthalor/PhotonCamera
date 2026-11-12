package com.hinnka.mycamera.stabilization

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val HORIZONTAL_LOCK_FILTER_TIME_CONSTANT_SECONDS = 0.055f
private const val HORIZONTAL_LOCK_MAX_SAMPLE_GAP_NANOS = 300_000_000L
private const val HORIZONTAL_LOCK_CROP_SAFETY_MARGIN = 1.015f

/**
 * Shared horizon-lock state used by the preview and encoder renderers.
 *
 * A continuous floating-point gravity angle is filtered here, away from the UI thread.
 * Preview and encoder GL threads both consume the same volatile correction value.
 */
object VideoHorizontalLockState {
    @Volatile
    var isEnabled: Boolean = false
        private set

    @Volatile
    var correctionDegrees: Float = 0f
        private set

    private var referenceOrientationDegrees = 0f
    private var smoothedOrientationDegrees = Float.NaN
    private var lastOrientationTimestampNanos = 0L

    @Synchronized
    fun setEnabled(enabled: Boolean, currentOrientationDegrees: Float) {
        if (enabled && !isEnabled) {
            val current = if (smoothedOrientationDegrees.isFinite()) {
                normalizeHorizontalLockOrientationDegrees(smoothedOrientationDegrees)
            } else {
                normalizeHorizontalLockOrientationDegrees(currentOrientationDegrees)
            }
            smoothedOrientationDegrees = current
            referenceOrientationDegrees = current
            correctionDegrees = 0f
        } else if (enabled && smoothedOrientationDegrees.isFinite()) {
            correctionDegrees = resolveHorizontalLockCorrection(
                referenceOrientationDegrees,
                smoothedOrientationDegrees,
            )
        } else if (!enabled) {
            correctionDegrees = 0f
        }
        isEnabled = enabled
    }

    /** Feed one sensor sample; this method never triggers UI recomposition. */
    @Synchronized
    fun updateOrientation(currentOrientationDegrees: Float, timestampNanos: Long) {
        if (!currentOrientationDegrees.isFinite()) return

        val normalized = normalizeHorizontalLockOrientationDegrees(currentOrientationDegrees)
        val previous = smoothedOrientationDegrees
        val deltaNanos = timestampNanos - lastOrientationTimestampNanos
        smoothedOrientationDegrees = if (
            !previous.isFinite() ||
            lastOrientationTimestampNanos <= 0L ||
            timestampNanos <= lastOrientationTimestampNanos ||
            deltaNanos > HORIZONTAL_LOCK_MAX_SAMPLE_GAP_NANOS
        ) {
            normalized
        } else {
            smoothHorizontalLockOrientationDegrees(
                previousDegrees = previous,
                currentDegrees = normalized,
                deltaTimeSeconds = deltaNanos / 1_000_000_000f,
                timeConstantSeconds = HORIZONTAL_LOCK_FILTER_TIME_CONSTANT_SECONDS,
            )
        }
        lastOrientationTimestampNanos = timestampNanos

        if (isEnabled) {
            correctionDegrees = resolveHorizontalLockCorrection(
                referenceOrientationDegrees,
                smoothedOrientationDegrees,
            )
        }
    }
}

internal fun normalizeHorizontalLockOrientationDegrees(value: Float): Float {
    if (!value.isFinite()) return 0f
    return ((value % 360f) + 360f) % 360f
}

private fun shortestHorizontalLockAngleDelta(fromDegrees: Float, toDegrees: Float): Float {
    var delta = (toDegrees - fromDegrees) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
}

/** Time-based circular low-pass filter, including the 0/360-degree wraparound. */
internal fun smoothHorizontalLockOrientationDegrees(
    previousDegrees: Float,
    currentDegrees: Float,
    deltaTimeSeconds: Float,
    timeConstantSeconds: Float = HORIZONTAL_LOCK_FILTER_TIME_CONSTANT_SECONDS,
): Float {
    if (!previousDegrees.isFinite()) {
        return normalizeHorizontalLockOrientationDegrees(currentDegrees)
    }
    if (!currentDegrees.isFinite()) {
        return normalizeHorizontalLockOrientationDegrees(previousDegrees)
    }
    if (!deltaTimeSeconds.isFinite() || deltaTimeSeconds <= 0f) {
        return normalizeHorizontalLockOrientationDegrees(previousDegrees)
    }

    val tau = timeConstantSeconds.takeIf { it.isFinite() && it > 0f } ?: 0.001f
    val alpha = (deltaTimeSeconds / (tau + deltaTimeSeconds)).coerceIn(0f, 1f)
    val delta = shortestHorizontalLockAngleDelta(previousDegrees, currentDegrees)
    return normalizeHorizontalLockOrientationDegrees(previousDegrees + delta * alpha)
}

/**
 * Same gravity-vector angle convention as Android's OrientationEventListener, but retaining
 * floating-point precision. Null is returned when the device is too close to flat to infer roll.
 */
internal fun resolveHorizontalLockSensorOrientationDegrees(
    gravityX: Float,
    gravityY: Float,
    gravityZ: Float,
): Float? {
    if (!gravityX.isFinite() || !gravityY.isFinite() || !gravityZ.isFinite()) return null

    val x = -gravityX
    val y = -gravityY
    val z = -gravityZ
    val horizontalMagnitudeSquared = x * x + y * y
    if (horizontalMagnitudeSquared * 4f < z * z) return null

    val angleDegrees = Math.toDegrees(atan2(-y.toDouble(), x.toDouble())).toFloat()
    return normalizeHorizontalLockOrientationDegrees(90f - angleDegrees)
}

/** Returns the shortest counter-rotation needed to keep the initial horizon level. */
internal fun resolveHorizontalLockCorrection(
    referenceOrientationDegrees: Float,
    currentOrientationDegrees: Float,
): Float {
    if (!referenceOrientationDegrees.isFinite() || !currentOrientationDegrees.isFinite()) return 0f
    return -shortestHorizontalLockAngleDelta(
        referenceOrientationDegrees,
        currentOrientationDegrees,
    )
}

/**
 * Computes extra crop from the complete transform, including existing center-crop, calibration
 * rotation and mirroring. The inverse transform maps the four output corners to source space.
 */
internal fun computeHorizontalLockCropZoom(transformedMvpMatrix: FloatArray): Float {
    require(transformedMvpMatrix.size >= 16)
    val m00 = transformedMvpMatrix[0]
    val m01 = transformedMvpMatrix[4]
    val m10 = transformedMvpMatrix[1]
    val m11 = transformedMvpMatrix[5]
    if (!m00.isFinite() || !m01.isFinite() || !m10.isFinite() || !m11.isFinite()) return 1f

    val determinant = m00 * m11 - m01 * m10
    if (!determinant.isFinite() || abs(determinant) < 1e-6f) return 1f

    // Maximum absolute source-coordinate extent of all four inverse-mapped output corners.
    val inverseRow0 = abs(m11 / determinant) + abs(-m01 / determinant)
    val inverseRow1 = abs(-m10 / determinant) + abs(m00 / determinant)
    val requiredZoom = maxOf(1f, inverseRow0, inverseRow1)
    return if (requiredZoom > 1.0001f) {
        requiredZoom * HORIZONTAL_LOCK_CROP_SAFETY_MARGIN
    } else {
        1f
    }
}

/**
 * Applies horizon correction after the existing crop/orientation transform. Rotation is performed
 * in pixel-proportional output coordinates, then a covering crop prevents exposed black corners.
 */
internal fun applyHorizontalLockMvpMatrix(
    baseMvpMatrix: FloatArray,
    targetWidth: Int,
    targetHeight: Int,
    correctionDegrees: Float,
    output: FloatArray,
    rotationScratch: FloatArray,
): FloatArray {
    require(baseMvpMatrix.size >= 16 && output.size >= 16 && rotationScratch.size >= 16)
    if (targetWidth <= 0 || targetHeight <= 0 || !correctionDegrees.isFinite()) {
        System.arraycopy(baseMvpMatrix, 0, output, 0, 16)
        return output
    }

    var angle = correctionDegrees % 360f
    if (angle > 180f) angle -= 360f
    if (angle < -180f) angle += 360f
    if (abs(angle) < 0.01f) {
        System.arraycopy(baseMvpMatrix, 0, output, 0, 16)
        return output
    }

    val aspect = targetWidth.toFloat() / targetHeight.toFloat()
    val radians = Math.toRadians(angle.toDouble())
    val sine = sin(radians).toFloat()
    val cosine = cos(radians).toFloat()

    // Clip space is square. Convert rotation to pixel-proportional coordinates:
    // M = A^-1 * R * A, with A = scale(aspect, 1).
    for (index in 0 until 16) {
        rotationScratch[index] = 0f
    }
    rotationScratch[0] = cosine
    rotationScratch[1] = sine * aspect
    rotationScratch[4] = -sine / aspect
    rotationScratch[5] = cosine
    rotationScratch[10] = 1f
    rotationScratch[15] = 1f

    // Column-major 4x4 multiplication: output = rotationScratch * baseMvpMatrix.
    for (column in 0 until 4) {
        val columnOffset = column * 4
        for (row in 0 until 4) {
            var value = 0f
            for (component in 0 until 4) {
                value += rotationScratch[component * 4 + row] *
                    baseMvpMatrix[columnOffset + component]
            }
            output[columnOffset + row] = value
        }
    }

    val zoom = computeHorizontalLockCropZoom(output)
    // Post-multiply by a uniform X/Y scale (Z remains 1), matching Matrix.scaleM semantics.
    for (row in 0 until 4) {
        output[row] *= zoom
        output[4 + row] *= zoom
    }
    return output
}
