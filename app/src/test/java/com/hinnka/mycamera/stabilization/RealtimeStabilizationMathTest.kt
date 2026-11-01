package com.hinnka.mycamera.stabilization

import android.hardware.camera2.CameraCharacteristics
import com.hinnka.mycamera.lut.MgcEisMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeStabilizationMathTest {
    @Test
    fun enhancedStabilizationSupportsFrontAndRearCamerasOnly() {
        assertTrue(isMgcStabilizationLensFacingSupported(CameraCharacteristics.LENS_FACING_FRONT))
        assertTrue(isMgcStabilizationLensFacingSupported(CameraCharacteristics.LENS_FACING_BACK))
        assertFalse(isMgcStabilizationLensFacingSupported(CameraCharacteristics.LENS_FACING_EXTERNAL))
        assertFalse(isMgcStabilizationLensFacingSupported(null))
    }

    @Test
    fun stabilizationLookaheadIsClampedToThreeThroughThirtyFrames() {
        assertEquals(3, normalizeStabilizationLookahead(0))
        assertEquals(3, normalizeStabilizationLookahead(2))
        assertEquals(3, normalizeStabilizationLookahead(3))
        assertEquals(10, normalizeStabilizationLookahead(10))
        assertEquals(30, normalizeStabilizationLookahead(30))
        assertEquals(30, normalizeStabilizationLookahead(31))
        assertEquals(30, normalizeStabilizationLookahead(Int.MAX_VALUE))
        assertEquals(7, normalizeStabilizationLookahead(DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD))
    }

    @Test
    fun stabilizationStrengthSupportsZeroThroughTwoHundredPercent() {
        assertEquals(0f, normalizeStabilizationStrength(0f))
        assertEquals(0.5f, normalizeStabilizationStrength(0.5f))
        assertEquals(1f, normalizeStabilizationStrength(1f))
        assertEquals(1.5f, normalizeStabilizationStrength(1.5f))
        assertEquals(2f, normalizeStabilizationStrength(2f))
        assertEquals(2f, normalizeStabilizationStrength(2.5f))
        assertEquals(0f, normalizeStabilizationStrength(-0.5f))
        assertEquals(
            DEFAULT_VIDEO_STABILIZATION_STRENGTH,
            normalizeStabilizationStrength(Float.NaN),
        )
    }

    @Test
    fun stabilizationCropHeadroomScalesAboveOneHundredPercent() {
        assertEquals(1f / 0.9f, resolveVideoStabilizationCropZoom(0f), 1.0e-5f)
        assertEquals(1f / 0.9f, resolveVideoStabilizationCropZoom(1f), 1.0e-5f)
        assertEquals(1f / 0.85f, resolveVideoStabilizationCropZoom(1.5f), 1.0e-5f)
        assertEquals(1.25f, resolveVideoStabilizationCropZoom(2f), 1.0e-5f)
    }

    @Test
    fun hybridOisRequiresTimestampedOpticalTelemetry() {
        assertFalse(
            canFuseOpticalStabilization(
                oisModeAvailable = true,
                supportsOisSamples = false,
                supportsLensIntrinsicsSamples = false,
            ),
        )
        assertTrue(
            canFuseOpticalStabilization(
                oisModeAvailable = true,
                supportsOisSamples = true,
                supportsLensIntrinsicsSamples = false,
            ),
        )
        assertTrue(
            canFuseOpticalStabilization(
                oisModeAvailable = true,
                supportsOisSamples = false,
                supportsLensIntrinsicsSamples = true,
            ),
        )
        assertFalse(
            canFuseOpticalStabilization(
                oisModeAvailable = false,
                supportsOisSamples = true,
                supportsLensIntrinsicsSamples = true,
            ),
        )
    }

    @Test
    fun opticalShiftSanityLimitsRejectLargeAndNonFiniteOffsets() {
        assertTrue(isPlausibleOisShift(40f, -40f, 1000, 1000))
        assertFalse(isPlausibleOisShift(51f, 0f, 1000, 1000))
        assertFalse(isPlausibleOisShift(Float.NaN, 0f, 1000, 1000))
        assertFalse(isPlausibleOisShift(0f, 0f, 0, 1000))

        assertTrue(isPlausibleOpticalCenterShift(40f, -40f, 40f, 1000, 1000))
        assertFalse(isPlausibleOpticalCenterShift(0f, 51f, 0f, 1000, 1000))
        assertFalse(isPlausibleOpticalCenterShift(0f, 0f, Float.POSITIVE_INFINITY, 1000, 1000))
    }

    @Test
    fun identityNativeMatricesReproduceMgcTwentySixVertexStrip() {
        val matrices = FloatArray(STABILIZATION_ROW_COUNT * 9)
        repeat(STABILIZATION_ROW_COUNT) { row ->
            matrices[row * 9] = 1f
            matrices[row * 9 + 4] = 1f
            matrices[row * 9 + 8] = 1f
        }

        val positions = MgcEisMesh.warpedPositions(matrices)

        assertEquals((STABILIZATION_ROW_COUNT + 1) * 2 * 4, positions.size)
        assertEquals(-1f, positions[0], 0f)
        assertEquals(1f, positions[1], 0f)
        assertEquals(1f, positions[3], 0f)
        assertEquals(1f, positions[positions.size - 4], 0f)
        assertEquals(-1f, positions[positions.size - 3], 0f)
        assertEquals(1f, positions[positions.size - 1], 0f)
        assertEquals(STABILIZATION_ROW_COUNT * 6, MgcEisMesh.indices.size)
    }

    @Test
    fun rearNinetyDegreeSensorUsesMgcRawGyroOrder() {
        assertTripleEquals(
            Triple(1.0, 2.0, 3.0),
            remapMgcGyroForTest(1.0, 2.0, 3.0, 90, frontFacing = false),
        )
    }

    @Test
    fun rearTwoSeventyDegreeSensorNegatesMgcXYOnly() {
        assertTripleEquals(
            Triple(-1.0, -2.0, 3.0),
            remapMgcGyroForTest(1.0, 2.0, 3.0, 270, frontFacing = false),
        )
    }

    @Test
    fun frontZeroDegreeSensorUsesMgcAxisSwap() {
        assertTripleEquals(
            Triple(2.0, -1.0, 3.0),
            remapMgcGyroForTest(1.0, 2.0, 3.0, 0, frontFacing = true),
        )
    }

    private fun assertTripleEquals(
        expected: Triple<Double, Double, Double>,
        actual: Triple<Double, Double, Double>,
    ) {
        assertEquals(expected.first, actual.first, 1.0e-9)
        assertEquals(expected.second, actual.second, 1.0e-9)
        assertEquals(expected.third, actual.third, 1.0e-9)
    }
}
