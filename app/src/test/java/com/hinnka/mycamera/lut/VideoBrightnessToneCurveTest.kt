package com.hinnka.mycamera.lut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBrightnessToneCurveTest {
    @Test
    fun zeroBrightnessIsAnIdentityCurve() {
        val lut = VideoBrightnessToneCurve.createRgbaBuffer(0f)
        for (index in 0 until VideoBrightnessToneCurve.LUT_SIZE) {
            val sample = lut.get(index * 4).toInt() and 0xFF
            assertEquals("LUT sample $index", index, sample)
            assertEquals(sample, lut.get(index * 4 + 1).toInt() and 0xFF)
            assertEquals(sample, lut.get(index * 4 + 2).toInt() and 0xFF)
            assertEquals(255, lut.get(index * 4 + 3).toInt() and 0xFF)
        }
    }

    @Test
    fun curveIsMonotonicAndOnlyTrueWhiteMapsTo255() {
        for (strength in listOf(1f, 25f, 50f, 75f, 100f)) {
            val lut = VideoBrightnessToneCurve.createRgbaBuffer(strength)
            var previous = 0
            for (index in 0 until VideoBrightnessToneCurve.LUT_SIZE) {
                val sample = lut.get(index * 4).toInt() and 0xFF
                assertTrue("strength=$strength index=$index", sample >= previous)
                if (index < VideoBrightnessToneCurve.LUT_SIZE - 1) {
                    assertTrue(
                        "near-white input must retain highlight headroom: strength=$strength index=$index sample=$sample",
                        sample < 255,
                    )
                }
                previous = sample
            }
            assertEquals(255, previous)
        }
    }

    @Test
    fun brightnessLiftsShadowsAndMidtonesWithoutChangingWhitePoint() {
        val lut = VideoBrightnessToneCurve.createRgbaBuffer(100f)
        val shadow = lut.get(32 * 4).toInt() and 0xFF
        val midtone = lut.get(128 * 4).toInt() and 0xFF
        val highlight = lut.get(240 * 4).toInt() and 0xFF
        val white = lut.get(255 * 4).toInt() and 0xFF

        assertTrue("shadows should be lifted", shadow > 32)
        assertTrue("midtones should be lifted", midtone > 128)
        assertTrue("highlights should roll off without clipping", highlight in 240..254)
        assertEquals("white point remains at 255", 255, white)
    }
}
