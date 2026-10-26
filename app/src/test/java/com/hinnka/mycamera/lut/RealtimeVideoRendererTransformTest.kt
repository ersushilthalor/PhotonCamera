package com.hinnka.mycamera.lut

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeVideoRendererTransformTest {
    @Test
    fun identityAndFlipDoNotSwapAxes() {
        assertFalse(
            isTextureTransformAxesSwapped(
                floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, -1f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 1f, 0f, 1f,
                )
            )
        )
    }

    @Test
    fun quarterTurnSwapsAxes() {
        assertTrue(
            isTextureTransformAxesSwapped(
                floatArrayOf(
                    0f, 1f, 0f, 0f,
                    1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, 0f, 1f,
                )
            )
        )
    }

    @Test
    fun centerCropUsesActualSwitchedLensInputGeometry() {
        // A 4:3 YUV input from the switched lens must be center-cropped to 16:9.
        assertArrayEquals(
            floatArrayOf(0f, 0.125f, 1f, 0.875f),
            resolveCenterCropRect(
                sourceWidth = 1920,
                sourceHeight = 1440,
                targetWidth = 3840,
                targetHeight = 2160,
            ),
            0.0001f,
        )

        // A quarter-turn changes which axis is cropped; it must not reuse the
        // original lens's landscape crop values.
        assertArrayEquals(
            floatArrayOf(0f, 0.2890625f, 1f, 0.7109375f),
            resolveCenterCropRect(
                sourceWidth = 1440,
                sourceHeight = 1920,
                targetWidth = 3840,
                targetHeight = 2160,
            ),
            0.0001f,
        )
    }

    @Test
    fun eightMpFourThreeLandscapeInputUsesSensorRotationForPortrait4k() {
        val identityMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        assertTrue(
            shouldApplySensorOrientationFallback(
                inputWidth = 3264,
                inputHeight = 2448,
                targetWidth = 2160,
                targetHeight = 3840,
                sensorOrientationDegrees = 270,
                textureTransformMatrix = identityMatrix,
            )
        )
    }

    @Test
    fun sensorOrientationFallbackDoesNotDoubleRotateOrApplyToUnrelatedStreams() {
        val quarterTurnMatrix = floatArrayOf(
            0f, 1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        val identityMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        assertFalse(
            shouldApplySensorOrientationFallback(
                3264, 2448, 2160, 3840, 270, quarterTurnMatrix,
            )
        )
        assertFalse(
            shouldApplySensorOrientationFallback(
                1920, 1080, 2160, 3840, 270, identityMatrix,
            )
        )
        assertFalse(
            shouldApplySensorOrientationFallback(
                3264, 2448, 3840, 2160, 270, identityMatrix,
            )
        )
        assertFalse(
            shouldApplySensorOrientationFallback(
                3264, 2448, 2160, 3840, 0, identityMatrix,
            )
        )
    }

    @Test
    fun frontEightMpFourThreeToPortrait4kKeepsCropInOutputQuadAxes() {
        val quarterTurnMatrix = floatArrayOf(
            0f, 1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        // The actual renderer helper must crop the oriented 3:4 source horizontally
        // in output-quad space. Swapping this result into a Y crop is a regression.
        assertArrayEquals(
            floatArrayOf(0.125f, 0f, 0.875f, 1f),
            resolveOutputQuadCropRect(
                presentationMatrix = quarterTurnMatrix,
                inputWidth = 3264,
                inputHeight = 2448,
                targetWidth = 2160,
                targetHeight = 3840,
            ),
            0.0001f,
        )
    }

    @Test
    fun unrotatedEightMpSourceUsesItsUnrotatedCropGeometry() {
        val identityMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        assertArrayEquals(
            floatArrayOf(0.2890625f, 0f, 0.7109375f, 1f),
            resolveOutputQuadCropRect(
                presentationMatrix = identityMatrix,
                inputWidth = 3264,
                inputHeight = 2448,
                targetWidth = 2160,
                targetHeight = 3840,
            ),
            0.0001f,
        )
    }

    @Test
    fun landscapeRearCameraCropRemainsUnchanged() {
        val identityMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        assertArrayEquals(
            floatArrayOf(0f, 0.125f, 1f, 0.875f),
            resolveOutputQuadCropRect(
                presentationMatrix = identityMatrix,
                inputWidth = 3264,
                inputHeight = 2448,
                targetWidth = 3840,
                targetHeight = 2160,
            ),
            0.0001f,
        )
    }

    @Test
    fun sensorOrientationBuildsRawYuvPresentationTransform() {
        assertMappedCorners(
            orientationDegrees = 0,
            bottomLeft = floatArrayOf(0f, 0f),
            bottomRight = floatArrayOf(1f, 0f),
            topLeft = floatArrayOf(0f, 1f),
        )
        assertMappedCorners(
            orientationDegrees = 90,
            bottomLeft = floatArrayOf(1f, 0f),
            bottomRight = floatArrayOf(1f, 1f),
            topLeft = floatArrayOf(0f, 0f),
        )
        assertMappedCorners(
            orientationDegrees = 180,
            bottomLeft = floatArrayOf(1f, 1f),
            bottomRight = floatArrayOf(0f, 1f),
            topLeft = floatArrayOf(1f, 0f),
        )
        assertMappedCorners(
            orientationDegrees = 270,
            bottomLeft = floatArrayOf(0f, 1f),
            bottomRight = floatArrayOf(0f, 0f),
            topLeft = floatArrayOf(1f, 1f),
        )
    }

    @Test
    fun sensorOrientationMustBeCardinal() {
        assertNull(resolveMgcEisPresentationMatrix(45))
    }

    private fun assertMappedCorners(
        orientationDegrees: Int,
        bottomLeft: FloatArray,
        bottomRight: FloatArray,
        topLeft: FloatArray,
    ) {
        val matrix = requireNotNull(resolveMgcEisPresentationMatrix(orientationDegrees))
        assertArrayEquals(bottomLeft, map(matrix, 0f, 0f), 0f)
        assertArrayEquals(bottomRight, map(matrix, 1f, 0f), 0f)
        assertArrayEquals(topLeft, map(matrix, 0f, 1f), 0f)
        assertTrue(isTextureTransformAxesSwapped(matrix) == (orientationDegrees % 180 != 0))
    }

    private fun map(matrix: FloatArray, x: Float, y: Float): FloatArray {
        return floatArrayOf(
            matrix[0] * x + matrix[4] * y + matrix[12],
            matrix[1] * x + matrix[5] * y + matrix[13],
        )
    }
}
