package com.hinnka.mycamera.stabilization

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoHorizontalLockStateTest {
    @Test
    fun correctionTracksSmallClockwiseAndCounterClockwiseRoll() {
        assertEquals(
            -12f,
            resolveHorizontalLockCorrection(30f, 42f),
            0.001f,
        )
        assertEquals(
            12f,
            resolveHorizontalLockCorrection(30f, 18f),
            0.001f,
        )
    }

    @Test
    fun correctionUsesShortestPathAcrossZeroDegrees() {
        assertEquals(
            -2f,
            resolveHorizontalLockCorrection(359f, 1f),
            0.001f,
        )
        assertEquals(
            2f,
            resolveHorizontalLockCorrection(1f, 359f),
            0.001f,
        )
    }

    @Test
    fun correctionUsesShortestPathAtHalfTurnBoundary() {
        assertEquals(
            -179f,
            resolveHorizontalLockCorrection(0f, 179f),
            0.001f,
        )
        assertEquals(
            179f,
            resolveHorizontalLockCorrection(0f, -179f),
            0.001f,
        )
    }

    @Test
    fun invalidOrientationDoesNotProduceNanCorrection() {
        assertEquals(
            0f,
            resolveHorizontalLockCorrection(Float.NaN, 10f),
            0f,
        )
        assertEquals(
            0f,
            resolveHorizontalLockCorrection(10f, Float.POSITIVE_INFINITY),
            0f,
        )
    }
}
