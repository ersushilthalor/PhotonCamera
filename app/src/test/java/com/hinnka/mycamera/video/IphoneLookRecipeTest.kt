package com.hinnka.mycamera.video

import com.hinnka.mycamera.model.ColorRecipeParams
import org.junit.Assert.assertEquals
import org.junit.Test

class IphoneLookRecipeTest {
    @Test
    fun appliesIphoneToneAndColourTuningWhilePreservingOtherRecipeFields() {
        val base = ColorRecipeParams(
            exposure = -0.5f,
            contrast = 1.2f,
            tonality = -0.2f,
            shadows = -0.4f,
            highlights = 0.4f,
            color = 0.1f,
            saturation = 1.2f,
            sharpness = -0.1f,
            temperature = 0.25f,
            noise = 0.35f,
        )

        val result = IphoneLookRecipe.apply(base)

        assertEquals(IphoneLookRecipe.EXPOSURE, result.exposure, 0.0001f)
        assertEquals(IphoneLookRecipe.TONALITY, result.tonality, 0.0001f)
        assertEquals(IphoneLookRecipe.CONTRAST, result.contrast, 0.0001f)
        assertEquals(IphoneLookRecipe.SHADOWS, result.shadows, 0.0001f)
        assertEquals(0.1f, result.highlights, 0.0001f)
        assertEquals(0.3f, result.color, 0.0001f)
        assertEquals(1.248f, result.saturation, 0.0001f)
        assertEquals(0.05f, result.sharpness, 0.0001f)
        assertEquals(base.temperature, result.temperature, 0.0001f)
        assertEquals(base.noise, result.noise, 0.0001f)
    }

    @Test
    fun additiveControlsAreClampedToSupportedRanges() {
        val base = ColorRecipeParams(
            highlights = -0.95f,
            color = 0.95f,
            saturation = 2.0f,
            sharpness = 0.95f,
        )

        val result = IphoneLookRecipe.apply(base)

        assertEquals(-1.0f, result.highlights, 0.0001f)
        assertEquals(1.0f, result.color, 0.0001f)
        assertEquals(2.0f, result.saturation, 0.0001f)
        assertEquals(1.0f, result.sharpness, 0.0001f)
    }
}
