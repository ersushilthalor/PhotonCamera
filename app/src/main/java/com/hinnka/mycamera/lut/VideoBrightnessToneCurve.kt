package com.hinnka.mycamera.lut

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Shared 256-entry post-processing luminance curve used by the camera preview
 * and the video editor/export renderer.
 */
internal object VideoBrightnessToneCurve {
    const val LUT_SIZE = 256
    private const val KNEE_START = 0.8f

    /**
     * Common shader implementation: both the Camera2 preview and Media3 video
     * editor/export sample the same LUT and use the same luminance-preserving gamut guard.
     * The caller defines getLuma(vec3).
     */
    val GLSL = """
        uniform highp sampler2D uBrightnessLut;
        uniform int uBrightnessEnabled;

        vec3 applyVideoBrightness(vec3 inputColor) {
            if (uBrightnessEnabled == 0) {
                return inputColor;
            }

            vec3 base = clamp(inputColor, 0.0, 1.0);
            float sourceLuma = clamp(getLuma(base), 0.0, 1.0);
            float lutU = (sourceLuma * 255.0 + 0.5) / 256.0;
            float targetLuma = texture(uBrightnessLut, vec2(lutU, 0.5)).r;
            vec3 chroma = base - vec3(sourceLuma);
            float chromaScale = 1.0;

            if (chroma.r > 0.00001) chromaScale = min(chromaScale, (1.0 - targetLuma) / chroma.r);
            else if (chroma.r < -0.00001) chromaScale = min(chromaScale, targetLuma / -chroma.r);
            if (chroma.g > 0.00001) chromaScale = min(chromaScale, (1.0 - targetLuma) / chroma.g);
            else if (chroma.g < -0.00001) chromaScale = min(chromaScale, targetLuma / -chroma.g);
            if (chroma.b > 0.00001) chromaScale = min(chromaScale, (1.0 - targetLuma) / chroma.b);
            else if (chroma.b < -0.00001) chromaScale = min(chromaScale, targetLuma / -chroma.b);

            return vec3(targetLuma) + chroma * clamp(chromaScale, 0.0, 1.0);
        }
    """.trimIndent()

    fun createRgbaBuffer(value: Float): ByteBuffer {
        val brightness = if (value.isFinite()) value.coerceIn(0f, 100f) else 0f
        val amount = brightness / 100f
        val gamma = 1f + 0.8f * amount
        val inverseGamma = 1.0 / gamma.toDouble()
        val kneeValue = KNEE_START.toDouble().pow(inverseGamma).toFloat()
        val kneeSlope = (
            inverseGamma * KNEE_START.toDouble().pow(inverseGamma - 1.0)
        ).toFloat()
        // Match the gamma segment's incoming slope at 0.8, then ease to a flat
        // derivative at white. This avoids a visible kink and retains highlight detail.
        val kneeStartTangent = (
            0.2f * kneeSlope / (1f - kneeValue).coerceAtLeast(0.000001f)
        ).coerceIn(0f, 3f)
        val buffer = ByteBuffer.allocateDirect(LUT_SIZE * 4).order(ByteOrder.nativeOrder())

        for (index in 0 until LUT_SIZE) {
            val input = index / (LUT_SIZE - 1f)
            val output = when {
                amount <= 0f -> input
                input <= KNEE_START -> input.toDouble().pow(inverseGamma).toFloat()
                else -> {
                    val t = ((input - KNEE_START) / (1f - KNEE_START)).coerceIn(0f, 1f)
                    val t2 = t * t
                    val t3 = t2 * t
                    val smoothEndpoint = -2f * t3 + 3f * t2
                    val matchedSlope = t3 - 2f * t2 + t
                    kneeValue + (1f - kneeValue) * (
                        smoothEndpoint + kneeStartTangent * matchedSlope
                    )
                }
            }
            // Keep 255 exclusive to the true white input. This preserves the
            // last highlight steps instead of turning near-white detail into a flat cap.
            val boundedOutput = if (index == LUT_SIZE - 1) {
                1f
            } else {
                output.coerceIn(0f, 254f / 255f)
            }
            val sample = (boundedOutput * 255f + 0.5f)
                .toInt()
                .coerceIn(0, 255)
                .toByte()
            buffer.put(sample)
            buffer.put(sample)
            buffer.put(sample)
            buffer.put(0xFF.toByte())
        }
        buffer.flip()
        return buffer
    }
}
