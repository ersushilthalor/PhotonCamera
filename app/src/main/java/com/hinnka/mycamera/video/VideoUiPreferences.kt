package com.hinnka.mycamera.video

import android.content.Context

enum class VideoFloatingWindowGlassStyle(val id: String) {
    LIQUID_GLASS("liquid_glass"),
    FROSTED_GLASS("frosted_glass"),
    TRANSPARENT_GLASS("transparent_glass"),
    SOLID_DARK("solid_dark");

    companion object {
        fun fromId(id: String?): VideoFloatingWindowGlassStyle =
            entries.firstOrNull { it.id == id } ?: LIQUID_GLASS
    }
}

data class VideoFloatingWindowAppearance(
    val glassStyle: VideoFloatingWindowGlassStyle = VideoFloatingWindowGlassStyle.LIQUID_GLASS,
    val transparency: Float = 0.55f,
    val blurStrength: Float = 22f,
    val windowScale: Float = 0.88f
) {
    fun normalized(): VideoFloatingWindowAppearance = copy(
        transparency = transparency.coerceIn(0f, 1f),
        blurStrength = blurStrength.coerceIn(0f, 50f),
        windowScale = windowScale.coerceIn(0.75f, 1.25f)
    )
}

/**
 * Persists the Pro Video UI mode and the shared floating-window appearance.
 * This is intentionally UI-only: camera configuration and recording stay on the existing
 * Camera2 video path.
 */
object VideoUiPreferences {
    private const val PREFS_NAME = "photon_video_ui"
    private const val KEY_PRO_VIDEO_MODE = "pro_video_mode"
    private const val KEY_GLASS_STYLE = "floating_glass_style"
    private const val KEY_TRANSPARENCY = "floating_transparency"
    private const val KEY_BLUR_STRENGTH = "floating_blur_strength"
    private const val KEY_WINDOW_SCALE = "floating_window_scale"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isProVideoModeEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_PRO_VIDEO_MODE, false)

    fun setProVideoModeEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_PRO_VIDEO_MODE, enabled).apply()
    }

    fun getFloatingWindowAppearance(context: Context): VideoFloatingWindowAppearance {
        val prefs = preferences(context)
        return VideoFloatingWindowAppearance(
            glassStyle = VideoFloatingWindowGlassStyle.fromId(prefs.getString(KEY_GLASS_STYLE, null)),
            transparency = prefs.getFloat(KEY_TRANSPARENCY, 0.55f),
            blurStrength = prefs.getFloat(KEY_BLUR_STRENGTH, 22f),
            windowScale = prefs.getFloat(KEY_WINDOW_SCALE, 0.88f)
        ).normalized()
    }

    fun saveFloatingWindowAppearance(
        context: Context,
        appearance: VideoFloatingWindowAppearance
    ) {
        val value = appearance.normalized()
        preferences(context).edit()
            .putString(KEY_GLASS_STYLE, value.glassStyle.id)
            .putFloat(KEY_TRANSPARENCY, value.transparency)
            .putFloat(KEY_BLUR_STRENGTH, value.blurStrength)
            .putFloat(KEY_WINDOW_SCALE, value.windowScale)
            .apply()
    }
}
