package com.hinnka.mycamera.camera

import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.util.Range
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Histogram-driven manual exposure for DX-HDR's long (A) frame.
 *
 * A frames target 1/100s for 50 Hz lighting and may lengthen only as far as 1/60s.
 * The short (B) frame is fixed near 1/6000s, clamped to the sensor's advertised range.
 */
internal class DxHdrExposureController(
    exposureRange: Range<Long>,
    sensitivityRange: Range<Int>,
    initialIso: Int?,
) {
    private val sensorExposureRange = exposureRange
    private val sensorSensitivityRange = sensitivityRange

    var longExposureNs: Long = PREFERRED_LONG_EXPOSURE_NS.coerceIn(
        exposureRange.lower,
        minOf(exposureRange.upper, MAX_LONG_EXPOSURE_NS),
    )
        private set
    var longIso: Int = (initialIso ?: sensitivityRange.lower)
        .coerceIn(sensitivityRange.lower, sensitivityRange.upper)
        private set

    val shortExposureNs: Long
        get() = SHORT_EXPOSURE_NS.coerceIn(
            sensorExposureRange.lower,
            sensorExposureRange.upper,
        )

    val shortIso: Int
        get() = longIso

    /** Returns true when the A-frame exposure changed enough to justify rebuilding the burst. */
    fun updateFromLongFrame(image: Image): Boolean {
        if (image.planes.isEmpty() || image.width <= 0 || image.height <= 0) return false
        val plane = image.planes[0]
        val source = plane.buffer.duplicate()
        val base = source.position()
        val limit = source.limit()
        val sampleX = (image.width / HISTOGRAM_SAMPLE_WIDTH).coerceAtLeast(1)
        val sampleY = (image.height / HISTOGRAM_SAMPLE_HEIGHT).coerceAtLeast(1)
        val histogram = IntArray(HISTOGRAM_BINS)
        var samples = 0

        var y = sampleY / 2
        while (y < image.height) {
            val rowOffset = base + y * plane.rowStride
            var x = sampleX / 2
            while (x < image.width) {
                val index = rowOffset + x * plane.pixelStride
                if (index < limit) {
                    val value = source.get(index).toInt() and 0xff
                    histogram[value * HISTOGRAM_BINS / 256]++
                    samples++
                }
                x += sampleX
            }
            y += sampleY
        }
        if (samples < MIN_HISTOGRAM_SAMPLES) return false

        // Median is less sensitive than the mean to a bright window or a dark foreground.
        val median = percentile(histogram, samples, 0.50f).coerceAtLeast(2) / 255f
        val targetMedian = TARGET_MEDIAN_LUMA
        val requestedFactor = (targetMedian / median).coerceIn(MIN_EXPOSURE_FACTOR, MAX_EXPOSURE_FACTOR)
        // Smooth the AE response to avoid visible pumping across alternating exposures.
        val factor = requestedFactor.toDouble().pow(AE_SMOOTHING_POWER).toFloat()
        val currentProduct = longExposureNs.toDouble() * longIso.toDouble()
        val targetProduct = currentProduct * factor.toDouble()

        val preferredExposure = PREFERRED_LONG_EXPOSURE_NS
            .coerceIn(sensorExposureRange.lower, maximumLongExposureNs)
        val preferredIso = targetProduct / preferredExposure.toDouble()
        val nextExposure: Long
        val nextIso: Int
        when {
            preferredIso in sensorSensitivityRange.lower.toDouble()..sensorSensitivityRange.upper.toDouble() -> {
                nextExposure = preferredExposure
                nextIso = preferredIso.roundToInt().coerceIn(
                    sensorSensitivityRange.lower,
                    sensorSensitivityRange.upper,
                )
            }
            preferredIso > sensorSensitivityRange.upper.toDouble() -> {
                nextExposure = maximumLongExposureNs
                nextIso = (targetProduct / nextExposure.toDouble()).roundToInt().coerceIn(
                    sensorSensitivityRange.lower,
                    sensorSensitivityRange.upper,
                )
            }
            else -> {
                nextIso = sensorSensitivityRange.lower
                nextExposure = (targetProduct / nextIso.toDouble())
                    .roundToLong()
                    .coerceIn(sensorExposureRange.lower, preferredExposure)
            }
        }

        val exposureChanged = relativeDifference(nextExposure, longExposureNs) >= EXPOSURE_UPDATE_THRESHOLD
        val isoChanged = relativeDifference(nextIso.toLong(), longIso.toLong()) >= EXPOSURE_UPDATE_THRESHOLD
        if (!exposureChanged && !isoChanged) return false

        longExposureNs = nextExposure.coerceAtMost(maximumLongExposureNs)
        longIso = nextIso
        return true
    }

    private val maximumLongExposureNs: Long
        get() = minOf(sensorExposureRange.upper, MAX_LONG_EXPOSURE_NS)

    private fun percentile(histogram: IntArray, total: Int, percentile: Float): Int {
        val target = (total * percentile).roundToInt().coerceAtLeast(1)
        var cumulative = 0
        histogram.forEachIndexed { bin, count ->
            cumulative += count
            if (cumulative >= target) {
                return ((bin + 0.5f) * (256f / HISTOGRAM_BINS)).roundToInt()
            }
        }
        return 255
    }

    private fun relativeDifference(first: Long, second: Long): Float {
        if (second <= 0L) return 1f
        return abs(first.toDouble() - second.toDouble()).toFloat() / second.toFloat()
    }

    companion object {
        const val PREFERRED_LONG_EXPOSURE_NS = 10_000_000L // 1/100s, 50 Hz mains
        const val MAX_LONG_EXPOSURE_NS = 16_666_666L // never slower than 1/60s
        const val SHORT_EXPOSURE_NS = 166_667L // approximately 1/6000s

        private const val HISTOGRAM_BINS = 64
        private const val HISTOGRAM_SAMPLE_WIDTH = 160
        private const val HISTOGRAM_SAMPLE_HEIGHT = 90
        private const val MIN_HISTOGRAM_SAMPLES = 32
        private const val TARGET_MEDIAN_LUMA = 0.18f
        private const val MIN_EXPOSURE_FACTOR = 0.5f
        private const val MAX_EXPOSURE_FACTOR = 2f
        private const val AE_SMOOTHING_POWER = 0.4
        private const val EXPOSURE_UPDATE_THRESHOLD = 0.035f

        fun sensorSupportsDxHdr(characteristics: CameraCharacteristics): Boolean {
            val available = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            ) ?: return false
            if (!available.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
                return false
            }
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?: return false
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?: return false
            return exposureRange.lower > 0L &&
                exposureRange.lower <= MAX_LONG_EXPOSURE_NS &&
                exposureRange.upper >= SHORT_EXPOSURE_NS &&
                isoRange.lower > 0 &&
                isoRange.upper >= isoRange.lower
        }
    }
}
