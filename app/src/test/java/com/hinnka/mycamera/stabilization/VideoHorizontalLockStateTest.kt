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

    @Test
    fun rotationDoesNotStretchNonSquareOutput() {
        listOf(1920 to 1080, 1080 to 1920, 1000 to 1000).forEach { (w, h) ->
            val aspect = w.toFloat() / h.toFloat()
            val base = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
            val out = FloatArray(16)
            applyHorizontalLockMvpMatrix(base, w, h, 17f, out, FloatArray(16))

            // Express the clip-space 2x2 block in pixel-proportional space: A * M * A^-1.
            val p00 = out[0]
            val p10 = out[1] / aspect
            val p01 = out[4] * aspect
            val p11 = out[5]

            // Must be a uniform scale times a pure rotation (no shear / anisotropic stretch).
            assertEquals(p00, p11, 1e-4f)
            assertEquals(p10, -p01, 1e-4f)
        }
    }

}
