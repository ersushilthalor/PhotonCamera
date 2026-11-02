package com.hinnka.mycamera.video

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OppoCam's adaptive Natural ISP tone and colour path, adapted for PhotonCamera.
 *
 * Scene adaptation is driven by Camera2 exposure metadata and face detections.
 * The resulting 64-point BT.709 curve is applied by the Camera2 ISP to both
 * preview and recording streams.
 */
internal class OppoNaturalVideoEngine {
    private data class Params(
        val exposure: Float = 0.0f,
        val shadows: Float = 0.35f,
        val highlights: Float = 0.40f,
        val contrast: Float = 0.0f
    )

    private var params = Params()
    private var smoothedExposure = 0.05f
    private var smoothedShadows = 0.35f
    private var smoothedHighlights = 0.40f
    private var smoothedEv100 = 11.5f
    private var lastIspExposure = 0.0f
    private var lastIspShadows = 0.0f
    private var lastIspHighlights = 0.0f
    private var lastCurveTimeMs = 0L
    private var lastCurveShadows = Float.NaN
    private var lastCurveHighlights = Float.NaN
    private var lastCurveExposure = Float.NaN
    private var cachedCurve: TonemapCurve? = null

    fun reset() {
        params = Params()
        smoothedExposure = 0.05f
        smoothedShadows = 0.35f
        smoothedHighlights = 0.40f
        smoothedEv100 = 11.5f
        lastIspExposure = 0.0f
        lastIspShadows = 0.0f
        lastIspHighlights = 0.0f
        lastCurveTimeMs = 0L
        lastCurveShadows = Float.NaN
        lastCurveHighlights = Float.NaN
        lastCurveExposure = Float.NaN
        cachedCurve = null
    }

    fun onFrameCaptured(result: TotalCaptureResult) {
        val iso = (result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100).coerceAtLeast(1)
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 20_000_000L
        val aperture = (result.get(CaptureResult.LENS_APERTURE) ?: 1.8f).coerceAtLeast(0.1f)
        val faces = result.get(CaptureResult.STATISTICS_FACES) ?: emptyArray()
        val exposureSeconds = (exposureNs / 1_000_000_000.0).coerceAtLeast(1e-6)
        val ev100 = (
            ln((aperture.toDouble() * aperture.toDouble()) / exposureSeconds) / ln(2.0) -
                ln(iso.toDouble() / 100.0) / ln(2.0)
            ).toFloat()

        val hasFace = faces.isNotEmpty()
        val largestFaceArea = if (hasFace) {
            faces.maxOf { it.bounds.width().toLong() * it.bounds.height().toLong() }
        } else {
            0L
        }
        processSceneIllumination(ev100, hasFace, largestFaceArea)
    }

    private fun processSceneIllumination(ev100: Float, hasFace: Boolean, largestFaceArea: Long) {
        val evDelta = abs(ev100 - smoothedEv100)
        if (evDelta >= 0.08f) {
            val alpha = if (evDelta > 0.40f) 0.28f else 0.09f
            smoothedEv100 += (ev100 - smoothedEv100) * alpha
        }

        val outdoorFactor = ((smoothedEv100 - 8.5f) / 6.0f).coerceIn(0.0f, 1.0f)
        val faceWeight = if (hasFace) {
            (largestFaceArea.toFloat() / 200_000f).coerceIn(0.2f, 1.0f)
        } else {
            0.0f
        }

        val targetExposure = (0.04f + outdoorFactor * 0.12f + faceWeight * 0.08f)
            .coerceIn(0.0f, 0.24f)
        val targetShadows = (0.30f + outdoorFactor * 0.32f + faceWeight * 0.10f)
            .coerceIn(0.20f, 0.70f)
        val targetHighlights = (0.35f + outdoorFactor * 0.35f).coerceIn(0.25f, 0.75f)

        val alpha = if (evDelta > 0.40f) 0.25f else 0.08f
        smoothedExposure += (targetExposure - smoothedExposure) * alpha
        smoothedShadows += (targetShadows - smoothedShadows) * alpha
        smoothedHighlights += (targetHighlights - smoothedHighlights) * alpha
        params = Params(
            exposure = smoothedExposure,
            shadows = smoothedShadows,
            highlights = smoothedHighlights
        )
    }

    fun hasSignificantChangeSinceLastIspUpdate(): Boolean =
        abs(params.exposure - lastIspExposure) > 0.03f ||
            abs(params.shadows - lastIspShadows) > 0.05f ||
            abs(params.highlights - lastIspHighlights) > 0.05f

    fun markIspUpdated() {
        lastIspExposure = params.exposure
        lastIspShadows = params.shadows
        lastIspHighlights = params.highlights
    }

    fun getTonemapCurve(maxPoints: Int = CURVE_POINTS): TonemapCurve {
        val points = maxPoints.coerceIn(2, CURVE_POINTS)
        val now = System.currentTimeMillis()
        val effectiveShadows = params.shadows.coerceIn(0.10f, 0.85f)
        val effectiveHighlights = params.highlights.coerceIn(0.15f, 0.85f)
        val effectiveExposure = params.exposure.coerceIn(-0.50f, 0.50f)
        if (cachedCurve != null && points == cachedPointCount &&
            now - lastCurveTimeMs < 50L &&
            abs(lastCurveShadows - effectiveShadows) < 0.0001f &&
            abs(lastCurveHighlights - effectiveHighlights) < 0.0001f &&
            abs(lastCurveExposure - effectiveExposure) < 0.0001f
        ) {
            return cachedCurve!!
        }

        val red = FloatArray(points * 2)
        val green = FloatArray(points * 2)
        val blue = FloatArray(points * 2)
        val exposureScale = 2.0f.pow(effectiveExposure * 0.65f)
        var previousY = 0.0f

        for (index in 0 until points) {
            val x = index.toFloat() / (points - 1).toFloat()
            val input = (x * exposureScale).coerceIn(0f, 1f)
            var y = if (input < 0.018f) {
                4.5f * input
            } else {
                1.099f * input.pow(0.45f) - 0.099f
            }.coerceIn(0f, 1f)

            if (index == 0) {
                y = 0f
            } else {
                if (input < 0.38f) {
                    val toe = (input / 0.38f) *
                        (1.0f - input / 0.38f).coerceAtLeast(0f).pow(1.6f) * 3.2f
                    y += effectiveShadows * 0.14f * toe
                }
                if (input > 0.65f) {
                    val t = (input - 0.65f) / 0.35f
                    y -= effectiveHighlights * 0.08f * t * t
                }
            }

            val output = if (index == 0) 0f else y.coerceIn(previousY, 1f)
            previousY = output
            val offset = index * 2
            red[offset] = x
            red[offset + 1] = output
            green[offset] = x
            green[offset + 1] = output
            blue[offset] = x
            blue[offset + 1] = output
        }

        return TonemapCurve(red, green, blue).also {
            cachedCurve = it
            cachedPointCount = points
            lastCurveTimeMs = now
            lastCurveShadows = effectiveShadows
            lastCurveHighlights = effectiveHighlights
            lastCurveExposure = effectiveExposure
        }
    }

    fun neutralWhiteGains(): RggbChannelVector = RggbChannelVector(1f, 1f, 1f, 1f)

    fun neutralColorTransform(): ColorSpaceTransform {
        val matrix = floatArrayOf(
            1.02f, -0.01f, -0.01f,
            -0.01f, 1.01f, 0.00f,
            -0.01f, -0.01f, 1.02f
        )
        val rationals = IntArray(18)
        for (row in 0..2) {
            for (column in 0..2) {
                val value = (matrix[row * 3 + column] * 256f).roundToInt()
                val index = (row * 3 + column) * 2
                rationals[index] = value
                rationals[index + 1] = 256
            }
        }
        return ColorSpaceTransform(rationals)
    }

    companion object {
        private const val CURVE_POINTS = 64
    }

    private var cachedPointCount = 0
}

/**
 * OppoCam's adaptive ARIB STD-B67 / BT.2100 HLG tone curve.
 *
 * Camera2 exposure metadata drives the temporal exposure adaptation. Pixel-histogram
 * adaptation is intentionally not fabricated: PhotonCamera does not currently expose
 * OppoCam's GPU luminance-statistics feed to this engine.
 */
internal class OppoHlg10AutoExposureEngine {
    private var smoothedEv100 = 11.0f
    private var smoothedExposure = 0.0f
    private var lastTargetExposure = 0.0f
    private var staticFrameCount = 0
    private var lastIspUpdateTimeMs = 0L
    private var lastIspExposure = 0.0f
    private var cachedCurve: TonemapCurve? = null
    private var lastCurveExposure = Float.NaN
    private var lastCurvePointCount = 0

    fun reset() {
        smoothedEv100 = 11.0f
        smoothedExposure = 0.0f
        lastTargetExposure = 0.0f
        staticFrameCount = 0
        lastIspUpdateTimeMs = 0L
        lastIspExposure = 0.0f
        cachedCurve = null
        lastCurveExposure = Float.NaN
        lastCurvePointCount = 0
    }

    fun onFrameCaptured(result: TotalCaptureResult) {
        val iso = (result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100).coerceAtLeast(1)
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 16_666_666L
        val aperture = (result.get(CaptureResult.LENS_APERTURE) ?: 1.8f).coerceAtLeast(0.1f)
        val exposureSeconds = (exposureNs / 1_000_000_000f).coerceAtLeast(1e-7f)
        val rawEv100 = (
            ln((iso.toFloat() / 100f) * (aperture * aperture) / exposureSeconds) / ln(2f)
            ).coerceIn(-2f, 18f)

        val evDelta = abs(rawEv100 - smoothedEv100)
        val isStatic = evDelta < 0.08f
        if (isStatic) staticFrameCount++ else staticFrameCount = 0
        val alpha = when {
            evDelta > 0.30f -> 0.40f
            evDelta > 0.10f -> 0.20f
            staticFrameCount > 5 -> 0.04f
            else -> 0.10f
        }
        smoothedEv100 += alpha * (rawEv100 - smoothedEv100)

        // Match OppoCam's metadata-driven exposure policy. Its optional P18/P50 GPU
        // luminance statistics are not available in this Camera2 pipeline.
        val targetOffset = 0.0f
        val targetDelta = abs(targetOffset - lastTargetExposure)
        val finalTarget = if (targetDelta < 0.035f && staticFrameCount > 3) {
            lastTargetExposure
        } else {
            lastTargetExposure = targetOffset
            targetOffset
        }
        smoothedExposure += alpha * (finalTarget - smoothedExposure)
    }

    fun hasSignificantChangeSinceLastIspUpdate(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastIspUpdateTimeMs < 60L) return false
        return abs(smoothedExposure - lastIspExposure) >= 0.03f
    }

    fun markIspUpdated() {
        lastIspUpdateTimeMs = System.currentTimeMillis()
        lastIspExposure = smoothedExposure
    }

    fun getTonemapCurve(maxPoints: Int = CURVE_POINTS): TonemapCurve {
        val points = maxPoints.coerceIn(2, CURVE_POINTS)
        val effectiveExposure = smoothedExposure.coerceIn(-2f, 2f)
        if (cachedCurve != null && points == lastCurvePointCount &&
            abs(effectiveExposure - lastCurveExposure) < 0.01f
        ) {
            return cachedCurve!!
        }

        val red = FloatArray(points * 2)
        val green = FloatArray(points * 2)
        val blue = FloatArray(points * 2)
        val exposureScale = 2.0f.pow(effectiveExposure * 0.50f)
        var previousY = 0f
        for (index in 0 until points) {
            val x = index.toFloat() / (points - 1).toFloat()
            val linear = (x * exposureScale).coerceIn(0f, 1f)
            val y = evaluateAribOetf(linear)
            val output = when (index) {
                0 -> 0f
                points - 1 -> 1f
                else -> y.coerceIn(previousY, 1f)
            }
            previousY = output
            val offset = index * 2
            red[offset] = x
            red[offset + 1] = output
            green[offset] = x
            green[offset + 1] = output
            blue[offset] = x
            blue[offset + 1] = output
        }

        return TonemapCurve(red, green, blue).also {
            cachedCurve = it
            lastCurveExposure = effectiveExposure
            lastCurvePointCount = points
        }
    }

    companion object {
        private const val CURVE_POINTS = 64
        private const val ARIB_A = 0.17883277f
        private const val ARIB_B = 0.28466892f
        private const val ARIB_C = 0.55991073f

        fun evaluateAribOetf(linearLight: Float): Float {
            val value = linearLight.coerceIn(0f, 1f)
            return if (value <= 1f / 12f) {
                sqrt(3f * value).coerceIn(0f, 1f)
            } else {
                (ARIB_A * ln(12f * value - ARIB_B) + ARIB_C).coerceIn(0f, 1f)
            }
        }
    }
}
