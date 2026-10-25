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
    fun quarterTurnMapsOrientedCropBackToRawTextureAxes() {
        // A 4:3 landscape input becomes 3:4 after a quarter-turn. Cropping that
        // oriented frame to 9:16 crops its X axis, which is the raw texture's Y axis.
        val orientedCrop = resolveCenterCropRect(
            sourceWidth = 1440,
            sourceHeight = 1920,
            targetWidth = 1080,
            targetHeight = 1920,
        )
        assertArrayEquals(
            floatArrayOf(0f, 0.125f, 1f, 0.875f),
            mapOrientedCropRectToTextureCoordinates(orientedCrop, axesSwapped = true),
            0.0001f,
        )
    }

    @Test
    fun nonRotatedCropKeepsItsTextureAxes() {
        val crop = floatArrayOf(0f, 0.125f, 1f, 0.875f)
        assertArrayEquals(
            crop,
            mapOrientedCropRectToTextureCoordinates(crop, axesSwapped = false),
            0f,
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
