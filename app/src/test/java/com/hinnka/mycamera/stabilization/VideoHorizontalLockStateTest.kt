package com.hinnka.mycamera.stabilization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VideoHorizontalLockStateTest {
    @Test
    fun correctionTracksSmallClockwiseAndCounterClockwiseRoll() {
        assertEquals(-12f, resolveHorizontalLockCorrection(30f, 42f), 0.001f)
        assertEquals(12f, resolveHorizontalLockCorrection(30f, 18f), 0.001f)
    }

    @Test
    fun correctionUsesShortestPathAcrossZeroDegrees() {
        assertEquals(-2f, resolveHorizontalLockCorrection(359f, 1f), 0.001f)
        assertEquals(2f, resolveHorizontalLockCorrection(1f, 359f), 0.001f)
    }

    @Test
    fun correctionUsesShortestPathAtHalfTurnBoundary() {
        assertEquals(-179f, resolveHorizontalLockCorrection(0f, 179f), 0.001f)
        assertEquals(179f, resolveHorizontalLockCorrection(0f, -179f), 0.001f)
    }

    @Test
    fun invalidOrientationDoesNotProduceNanCorrection() {
        assertEquals(0f, resolveHorizontalLockCorrection(Float.NaN, 10f), 0f)
        assertEquals(0f, resolveHorizontalLockCorrection(10f, Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun gravityVectorUsesPlatformOrientationConventionWithFloatPrecision() {
        assertEquals(0f, resolveHorizontalLockSensorOrientationDegrees(0f, 9.8f, 0f)!!, 0.001f)
        assertEquals(90f, resolveHorizontalLockSensorOrientationDegrees(-9.8f, 0f, 0f)!!, 0.001f)
        assertEquals(180f, resolveHorizontalLockSensorOrientationDegrees(0f, -9.8f, 0f)!!, 0.001f)
        assertEquals(270f, resolveHorizontalLockSensorOrientationDegrees(9.8f, 0f, 0f)!!, 0.001f)
    }

    @Test
    fun gravityVectorIsRejectedWhenDeviceIsNearlyFlat() {
        assertNull(resolveHorizontalLockSensorOrientationDegrees(0f, 0f, 9.8f))
    }

    @Test
    fun smoothingUsesShortestPathAcrossZeroDegrees() {
        val smoothed = smoothHorizontalLockOrientationDegrees(
            previousDegrees = 359f,
            currentDegrees = 1f,
            deltaTimeSeconds = 0.02f,
            timeConstantSeconds = 0.05f,
        )
        assertTrue(smoothed > 359f || smoothed < 1f)
        assertTrue(abs(resolveHorizontalLockCorrection(359f, smoothed)) < 2f)
    }

    @Test
    fun smoothingSuppressesSingleFrameAngularJitter() {
        val smoothed = smoothHorizontalLockOrientationDegrees(
            previousDegrees = 10f,
            currentDegrees = 25f,
            deltaTimeSeconds = 0.02f,
            timeConstantSeconds = 0.055f,
        )
        assertTrue(smoothed > 10f)
        assertTrue(smoothed < 25f)
    }

    @Test
    fun rotationDoesNotStretchNonSquareOutput() {
        listOf(1920 to 1080, 1080 to 1920, 1000 to 1000).forEach { (width, height) ->
            val aspect = width.toFloat() / height.toFloat()
            val output = FloatArray(16)
            applyHorizontalLockMvpMatrix(
                baseMvpMatrix = identityMatrix(),
                targetWidth = width,
                targetHeight = height,
                correctionDegrees = 17f,
                output = output,
                rotationScratch = FloatArray(16),
            )

            val p00 = output[0]
            val p10 = output[1] / aspect
            val p01 = output[4] * aspect
            val p11 = output[5]

            assertEquals(p00, p11, 1e-4f)
            assertEquals(p10, -p01, 1e-4f)
        }
    }

    @Test
    fun cropKeepsAllOutputCornersInsideSourceForBothOrientations() {
        val sizes = listOf(1920 to 1080, 1080 to 1920, 1000 to 1000)
        val angles = listOf(-45f, -17f, -1f, 1f, 17f, 45f)

        sizes.forEach { (width, height) ->
            angles.forEach { angle ->
                val output = FloatArray(16)
                applyHorizontalLockMvpMatrix(
                    baseMvpMatrix = identityMatrix(),
                    targetWidth = width,
                    targetHeight = height,
                    correctionDegrees = angle,
                    output = output,
                    rotationScratch = FloatArray(16),
                )

                val m00 = output[0]
                val m01 = output[4]
                val m10 = output[1]
                val m11 = output[5]
                val determinant = m00 * m11 - m01 * m10
                assertTrue(abs(determinant) > 1e-6f)

                for (x in listOf(-1f, 1f)) {
                    for (y in listOf(-1f, 1f)) {
                        val sourceX = (m11 * x - m01 * y) / determinant
                        val sourceY = (-m10 * x + m00 * y) / determinant
                        assertTrue(
                            "sourceX=" + sourceX + " outside at " + width + "x" + height + ", angle=" + angle,
                            abs(sourceX) <= 1.0001f,
                        )
                        assertTrue(
                            "sourceY=" + sourceY + " outside at " + width + "x" + height + ", angle=" + angle,
                            abs(sourceY) <= 1.0001f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun cropIsNeutralWhenThereIsNoRotation() {
        assertEquals(1f, computeHorizontalLockCropZoom(1920, 1080, 0f), 1e-6f)
    }

    private fun identityMatrix() = FloatArray(16).also { matrix ->
        matrix[0] = 1f
        matrix[5] = 1f
        matrix[10] = 1f
        matrix[15] = 1f
    }
}
