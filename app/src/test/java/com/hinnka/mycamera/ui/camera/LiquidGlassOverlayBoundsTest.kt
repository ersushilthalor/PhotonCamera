package com.hinnka.mycamera.ui.camera

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiquidGlassOverlayBoundsTest {
    @Test
    fun fullPreviewMapsToFullOpenGlViewport() {
        assertArrayEquals(
            floatArrayOf(0f, 0f, 1f, 1f, 0f),
            createLiquidGlassOverlayBounds(
                panelBounds = Rect(0f, 0f, 100f, 200f),
                previewBounds = Rect(0f, 0f, 100f, 200f),
            ),
            0.0001f,
        )
    }

    @Test
    fun panelMapsIntoBottomLeftTextureCoordinates() {
        assertArrayEquals(
            floatArrayOf(0.2f, 0.25f, 0.8f, 0.75f, 0f),
            createLiquidGlassOverlayBounds(
                panelBounds = Rect(20f, 25f, 80f, 75f),
                previewBounds = Rect(0f, 0f, 100f, 100f),
            ),
            0.0001f,
        )
    }

    @Test
    fun panelIsClippedToThePreviewViewport() {
        assertArrayEquals(
            floatArrayOf(0f, 0.25f, 1f, 0.75f, 0f),
            createLiquidGlassOverlayBounds(
                panelBounds = Rect(-20f, 25f, 120f, 75f),
                previewBounds = Rect(0f, 0f, 100f, 100f),
            ),
            0.0001f,
        )
    }

    @Test
    fun panelOutsidePreviewDoesNotCreateGlassOverlay() {
        assertNull(
            createLiquidGlassOverlayBounds(
                panelBounds = Rect(120f, 20f, 180f, 70f),
                previewBounds = Rect(0f, 0f, 100f, 100f),
            )
        )
    }

    @Test
    fun invalidOrEmptyPreviewDoesNotCreateGlassOverlay() {
        assertNull(
            createLiquidGlassOverlayBounds(
                panelBounds = Rect(0f, 0f, 100f, 100f),
                previewBounds = Rect(0f, 0f, 0f, 100f),
            )
        )
    }
}
