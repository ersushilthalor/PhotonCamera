package com.hinnka.mycamera.stabilization

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared horizon-lock state used by the preview and the encoder renderers.
 *
 * OrientationObserver supplies continuous device-roll angles. The reference is captured when
 * the user enables the feature, so the image stays at its starting horizon while the phone rolls.
 */
object VideoHorizontalLockState {
    @Volatile
    var isEnabled: Boolean = false
        private set

    @Volatile
    var correctionDegrees: Float = 0f
        private set

    private var referenceOrientationDegrees = 0f

    @Synchronized
    fun setEnabled(enabled: Boolean, currentOrientationDegrees: Float) {
        if (enabled && !isEnabled) {
            referenceOrientationDegrees = normalizeOrientation(currentOrientationDegrees)
            correctionDegrees = 0f
        } else if (enabled) {
            correctionDegrees = resolveHorizontalLockCorrection(
                referenceOrientationDegrees,
                currentOrientationDegrees,
            )
        } else {
            correctionDegrees = 0f
        }
        isEnabled = enabled
    }

    private fun normalizeOrientation(value: Float): Float {
        if (!value.isFinite()) return 0f
        return ((value % 360f) + 360f) % 360f
    }
}

/** Returns the shortest counter-rotation needed to keep the initial horizon level. */
internal fun resolveHorizontalLockCorrection(
    referenceOrientationDegrees: Float,
    currentOrientationDegrees: Float,
): Float {
    if (!referenceOrientationDegrees.isFinite() || !currentOrientationDegrees.isFinite()) return 0f
    var delta = (currentOrientationDegrees - referenceOrientationDegrees) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return -delta
}

/**
 * Applies horizon correction after the existing crop/orientation transform. The calculated zoom
 * keeps the rotated image covering the output rectangle, avoiding exposed black corners.
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

    // Clip space is a square [-1, 1] x [-1, 1] no matter what the output size is, so a plain
    // rotation there shears/stretches the image on any non-square target (16:9, 9:16, ...).
    // Rotate in pixel-proportional space instead: M = A^-1 * R * A with A = scale(aspect, 1).
    //   | cos   -sin/aspect |
    //   | sin*aspect   cos  |   (column-major below)
    val signedRadians = Math.toRadians(angle.toDouble())
    val signedSine = sin(signedRadians).toFloat()
    val signedCosine = cos(signedRadians).toFloat()
    // Build the rotation matrix directly so this math is deterministic in JVM tests and does
    // not depend on Android's android.opengl.Matrix stubs. Keep all work in caller-owned arrays:
    // this method runs for every preview and encoder frame.
    for (index in 0 until 16) {
        rotationScratch[index] = 0f
    }
    rotationScratch[0] = signedCosine
    rotationScratch[1] = signedSine * aspect
    rotationScratch[4] = -signedSine / aspect
    rotationScratch[5] = signedCosine
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

    val radians = Math.toRadians(abs(angle).toDouble())
    val sine = abs(sin(radians)).toFloat()
    val cosine = abs(cos(radians)).toFloat()
    val zoom = maxOf(
        1f,
        cosine + sine * aspect,
        cosine + sine / aspect,
    )

    // Post-multiply by a uniform X/Y scale (Z remains 1), matching Matrix.scaleM semantics.
    for (row in 0 until 4) {
        output[row] *= zoom
        output[4 + row] *= zoom
    }
    return output
}
