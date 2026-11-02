package com.hinnka.mycamera.video

/** Selects the video look independently from the camera Log/HDR transfer profile. */
enum class VideoLookProfile(val displayName: String) {
    OFF("Off"),
    IPHONE_NATURAL("iPhone Natural");

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        fun fromPersistedName(name: String?): VideoLookProfile =
            entries.firstOrNull { it.name == name } ?: IPHONE_NATURAL
    }
}
