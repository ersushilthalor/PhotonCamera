package com.hinnka.mycamera.stabilization

import android.opengl.Matrix
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
): FloatArray {
    require(baseMvpMatrix.size >= 16 && output.size >= 16)
    System.arraycopy(baseMvpMatrix, 0, output, 0, 16)
    if (targetWidth <= 0 || targetHeight <= 0 || !correctionDegrees.isFinite()) return output

    var angle = correctionDegrees % 360f
    if (angle > 180f) angle -= 360f
    if (angle < -180f) angle += 360f
    if (abs(angle) < 0.01f) return output

    Matrix.rotateM(output, 0, angle, 0f, 0f, 1f)

    val radians = Math.toRadians(abs(angle).toDouble())
    val sine = abs(sin(radians)).toFloat()
    val cosine = abs(cos(radians)).toFloat()
    val aspect = targetWidth.toFloat() / targetHeight.toFloat()
    val zoom = maxOf(
        1f,
        cosine + sine * aspect,
        cosine + sine / aspect,
    )
    Matrix.scaleM(output, 0, zoom, zoom, 1f)
    return output
}
