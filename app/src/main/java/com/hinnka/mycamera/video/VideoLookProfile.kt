package com.hinnka.mycamera.video

/**
 * Selects the video rendering look independently from the camera Log/HDR transfer profile.
 * The iPhone-inspired profile is a restrained Rec.709 transform, not an Apple camera clone.
 */
enum class VideoLookProfile(val displayName: String) {
    OFF("Off"),
    IPHONE_NATURAL("iPhone Natural");

    val isEnabled: Boolean
        get() = this != OFF

    val params: VideoProfileParams
        get() = when (this) {
            OFF -> VideoProfileParams.IDENTITY
            IPHONE_NATURAL -> VideoProfileParams.IPHONE_NATURAL
        }

    companion object {
        fun fromPersistedName(name: String?): VideoLookProfile =
            entries.firstOrNull { it.name == name } ?: IPHONE_NATURAL
    }
}

/** Immutable parameters shared by the viewfinder and the recording EGL renderer. */
data class VideoProfileParams(
    val contrast: Float,
    val saturation: Float,
    val shadowLift: Float,
    val highlightKnee: Float,
    val highlightShoulder: Float,
    /** Column-major 3x3 linear-light RGB transform for GLES glUniformMatrix3fv. */
    val colorMatrix: FloatArray,
) {
    companion object {
        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )

        val IDENTITY = VideoProfileParams(
            contrast = 1f,
            saturation = 1f,
            shadowLift = 0f,
            highlightKnee = 0.74f,
            highlightShoulder = 0f,
            colorMatrix = IDENTITY_MATRIX,
        )

        // Conservative initial values; device-specific tuning requires captured test footage.
        val IPHONE_NATURAL = VideoProfileParams(
            contrast = 0.96f,
            saturation = 0.97f,
            shadowLift = 0.012f,
            highlightKnee = 0.74f,
            highlightShoulder = 0.18f,
            colorMatrix = IDENTITY_MATRIX,
        )
    }
}
