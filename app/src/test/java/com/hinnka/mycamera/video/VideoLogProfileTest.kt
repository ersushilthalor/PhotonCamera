package com.hinnka.mycamera.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLogProfileTest {
    @Test
    fun selectableVideoProfilesStayInRequestedOrder() {
        assertEquals(
            listOf(VideoLogProfile.OFF, VideoLogProfile.APPLE_LOG2, VideoLogProfile.NATURAL, VideoLogProfile.HLG10),
            VideoLogProfile.SELECTABLE_PROFILES
        )
    }

    @Test
    fun offAndNaturalAreProcessedVideoRatherThanLog() {
        assertFalse(VideoLogProfile.OFF.isEnabled)
        assertFalse(VideoLogProfile.NATURAL.isEnabled)
        assertTrue(VideoLogProfile.APPLE_LOG2.isEnabled)
        assertTrue(VideoLogProfile.HLG10.isEnabled)
    }

    @Test
    fun newVideoConfigAndMissingPreferenceDefaultToLogOff() {
        assertEquals(VideoLogProfile.OFF, VideoConfig().logProfile)
        assertEquals(VideoLogProfile.OFF, VideoLogProfile.fromPersistedName(null))
    }

    @Test
    fun retiredProfilePreferencesMigrateToLogOff() {
        assertEquals(VideoLogProfile.OFF, VideoLogProfile.fromPersistedName("OFF"))
        assertEquals(VideoLogProfile.OFF, VideoLogProfile.fromPersistedName("FLOG2_BT2020"))
        assertEquals(VideoLogProfile.NATURAL, VideoLogProfile.fromPersistedName("NATURAL"))
        assertEquals(VideoLogProfile.APPLE_LOG2, VideoLogProfile.fromPersistedName("APPLE_LOG2"))
        assertEquals(VideoLogProfile.HLG10, VideoLogProfile.fromPersistedName("HLG10"))
    }

    @Test
    fun hlgUsesAribOetfAnchors() {
        assertEquals(0.0f, OppoHlg10AutoExposureEngine.evaluateAribOetf(0.0f), 0.0001f)
        assertEquals(0.5f, OppoHlg10AutoExposureEngine.evaluateAribOetf(1.0f / 12.0f), 0.001f)
        assertEquals(1.0f, OppoHlg10AutoExposureEngine.evaluateAribOetf(1.0f), 0.0001f)
    }
}
