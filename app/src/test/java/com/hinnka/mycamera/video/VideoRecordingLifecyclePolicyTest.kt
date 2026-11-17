package com.hinnka.mycamera.video

import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRecordingLifecyclePolicyTest {

    @Test
    fun cameraSwitchKeepsRequestedInputSizeWhenDestinationSupportsIt() {
        val supported = Size(1920, 1080)
        val capabilities = VideoCapabilities(
            cameraInputSizesByResolution = mapOf(VideoResolutionPreset.FHD_1080P to supported)
        )

        assertEquals(
            supported,
            capabilities.resolveCameraInputSize(
                requestedResolution = VideoResolutionPreset.FHD_1080P,
                enhancedStabilization = false,
                fallback = Size(1280, 720),
            )
        )
    }

    @Test
    fun cameraSwitchFallsBackToLargestSupportedInputSize() {
        val largest = Size(1920, 1080)
        val capabilities = VideoCapabilities(
            cameraInputSizesByResolution = mapOf(
                VideoResolutionPreset.HD_720P to Size(1280, 720),
                VideoResolutionPreset.FHD_1080P to largest,
            )
        )

        assertEquals(
            largest,
            capabilities.resolveCameraInputSize(
                requestedResolution = VideoResolutionPreset.UHD_2160P,
                enhancedStabilization = false,
                fallback = Size(640, 480),
            )
        )
    }

    @Test
    fun cameraInputIsAttachedOnlyWhileActivelyRecording() {
        assertTrue(VideoRecordingState(isRecording = true).shouldAttachCameraInput())
        assertFalse(
            VideoRecordingState(isRecording = true, isPaused = true).shouldAttachCameraInput()
        )
        assertFalse(VideoRecordingState(isProcessing = true).shouldAttachCameraInput())
        assertFalse(VideoRecordingState().shouldAttachCameraInput())
    }
}
