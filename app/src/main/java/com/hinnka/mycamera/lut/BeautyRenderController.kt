package com.hinnka.mycamera.lut

import android.opengl.GLES30
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe beauty settings shared by the preview GL thread and the dedicated video encoder
 * GL thread. Face coordinates are normalized to the sampled preview image (top-left origin).
 */
data class BeautyFaceRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val leftEyeX: Float,
    val leftEyeY: Float,
    val rightEyeX: Float,
    val rightEyeY: Float,
    val mouthX: Float,
    val mouthY: Float,
) {
    init {
        require(listOf(left, top, right, bottom, leftEyeX, leftEyeY, rightEyeX, rightEyeY, mouthX, mouthY)
            .all { it.isFinite() && it in 0f..1f })
        require(right > left && bottom > top)
    }

    companion object {
        fun fromLandmarks(points: List<Pair<Float, Float>>): BeautyFaceRegion? {
            if (points.size < 478) return null
            val ovalIndices = intArrayOf(
                10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288,
                397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136,
                172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109
            )
            val oval = ovalIndices.asIterable().mapNotNull { index -> points.getOrNull(index) }
            if (oval.size != ovalIndices.size) return null
            val left = oval.minOf { it.first }.coerceIn(0f, 1f)
            val top = oval.minOf { it.second }.coerceIn(0f, 1f)
            val right = oval.maxOf { it.first }.coerceIn(0f, 1f)
            val bottom = oval.maxOf { it.second }.coerceIn(0f, 1f)
            if (right - left < 0.04f || bottom - top < 0.04f) return null

            fun center(indices: IntArray): Pair<Float, Float> {
                val selected = indices.asIterable().mapNotNull { index -> points.getOrNull(index) }
                if (selected.isEmpty()) return 0.5f to 0.5f
                val x = selected.sumOf { it.first.toDouble() }.toFloat() / selected.size
                val y = selected.sumOf { it.second.toDouble() }.toFloat() / selected.size
                return x to y
            }

            val leftEye = center(intArrayOf(33, 133, 159, 145, 160, 144))
            val rightEye = center(intArrayOf(362, 263, 386, 374, 387, 373))
            val mouth = center(intArrayOf(13, 14, 61, 291))
            return BeautyFaceRegion(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                leftEyeX = leftEye.first.coerceIn(0f, 1f),
                leftEyeY = leftEye.second.coerceIn(0f, 1f),
                rightEyeX = rightEye.first.coerceIn(0f, 1f),
                rightEyeY = rightEye.second.coerceIn(0f, 1f),
                mouthX = mouth.first.coerceIn(0f, 1f),
                mouthY = mouth.second.coerceIn(0f, 1f),
            )
        }
    }
}

data class BeautyRenderState(
    val enabled: Boolean = false,
    val smoothing: Float = 0f,
    val faceRegion: BeautyFaceRegion? = null,
) {
    val active: Boolean
        get() = enabled && smoothing > 0.001f && faceRegion != null
}

class BeautyRenderController {
    private val state = AtomicReference(BeautyRenderState())

    fun snapshot(): BeautyRenderState = state.get()

    fun setEnabled(enabled: Boolean) {
        state.updateAndGet { it.copy(enabled = enabled) }
    }

    fun setSmoothing(value: Float) {
        state.updateAndGet { it.copy(smoothing = value.coerceIn(0f, 1f)) }
    }

    fun setFaceRegion(region: BeautyFaceRegion?) {
        state.updateAndGet { it.copy(faceRegion = region) }
    }

    fun clear() {
        state.set(BeautyRenderState())
    }
}


internal fun ColorPassLocations.bindBeauty(state: BeautyRenderState) {
    val region = state.faceRegion
    val strength = if (state.active) state.smoothing.coerceIn(0f, 1f) else 0f
    GLES30.glUniform1f(uBeautyStrengthLocation, strength)
    if (region == null || strength <= 0f) {
        GLES30.glUniform4f(uBeautyFaceRectLocation, 0f, 0f, 0f, 0f)
        GLES30.glUniform2f(uBeautyLeftEyeLocation, -1f, -1f)
        GLES30.glUniform2f(uBeautyRightEyeLocation, -1f, -1f)
        GLES30.glUniform2f(uBeautyMouthLocation, -1f, -1f)
        return
    }
    GLES30.glUniform4f(
        uBeautyFaceRectLocation,
        region.left, region.top, region.right, region.bottom,
    )
    GLES30.glUniform2f(uBeautyLeftEyeLocation, region.leftEyeX, region.leftEyeY)
    GLES30.glUniform2f(uBeautyRightEyeLocation, region.rightEyeX, region.rightEyeY)
    GLES30.glUniform2f(uBeautyMouthLocation, region.mouthX, region.mouthY)
}
