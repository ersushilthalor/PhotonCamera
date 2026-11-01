package com.hinnka.mycamera.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class OppoPhotoSettings(
    val autoHdrEnabled: Boolean = true,
    val fastShutterEnabled: Boolean = true,
    val fastShutterFps: Int = 20,
    val refocusEnabled: Boolean = false,
    val refocusFrameCount: Int = 5,
    val customPipelineEnabled: Boolean = false,
    val pipelineProfile: String = "NATURAL",
    val exposure: Float = 0f,
    val contrast: Float = 1f,
    val highlights: Float = -15f,
    val shadows: Float = 12f,
    val saturation: Float = 0f,
    val vibrance: Float = 5f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val sharpness: Float = 18f,
    val texture: Float = 0f,
    val noiseReduction: Float = 10f,
    val detailPreservation: Float = 88f,
) {
    fun normalized(): OppoPhotoSettings = copy(
        fastShutterFps = fastShutterFps.coerceIn(5, 20),
        refocusFrameCount = refocusFrameCount.coerceIn(3, 8),
        pipelineProfile = pipelineProfile.takeIf {
            it in setOf("NATURAL", "HASSELBLAD", "SAMSUNG", "PIXEL", "IPHONE", "CUSTOM")
        } ?: "NATURAL",
        exposure = exposure.coerceIn(-2f, 2f),
        contrast = contrast.coerceIn(0.6f, 1.4f),
        highlights = highlights.coerceIn(-100f, 100f),
        shadows = shadows.coerceIn(-100f, 100f),
        saturation = saturation.coerceIn(-100f, 100f),
        vibrance = vibrance.coerceIn(-100f, 100f),
        temperature = temperature.coerceIn(-100f, 100f),
        tint = tint.coerceIn(-100f, 100f),
        sharpness = sharpness.coerceIn(0f, 100f),
        texture = texture.coerceIn(-100f, 100f),
        noiseReduction = noiseReduction.coerceIn(0f, 100f),
        detailPreservation = detailPreservation.coerceIn(0f, 100f),
    )

    fun toPipelineJson(): String = JSONObject().apply {
        put("profile", pipelineProfile)
        put("exposure", exposure)
        put("contrast", contrast)
        put("highlights", highlights)
        put("shadows", shadows)
        put("saturation", saturation)
        put("vibrance", vibrance)
        put("temperature", temperature)
        put("tint", tint)
        put("sharpness", sharpness)
        put("texture", texture)
        put("noiseReduction", noiseReduction)
        put("detailPreservation", detailPreservation)
    }.toString()
}

class OppoPhotoSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "oppo_photo_features",
        Context.MODE_PRIVATE,
    )
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<OppoPhotoSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (OppoPhotoSettings) -> OppoPhotoSettings) {
        val updated = transform(_settings.value).normalized()
        _settings.value = updated
        preferences.edit()
            .putBoolean("auto_hdr", updated.autoHdrEnabled)
            .putBoolean("fast_shutter", updated.fastShutterEnabled)
            .putInt("fast_shutter_fps", updated.fastShutterFps)
            .putBoolean("refocus", updated.refocusEnabled)
            .putInt("refocus_frame_count", updated.refocusFrameCount)
            .putBoolean("custom_pipeline", updated.customPipelineEnabled)
            .putString("pipeline_profile", updated.pipelineProfile)
            .putFloat("pipeline_exposure", updated.exposure)
            .putFloat("pipeline_contrast", updated.contrast)
            .putFloat("pipeline_highlights", updated.highlights)
            .putFloat("pipeline_shadows", updated.shadows)
            .putFloat("pipeline_saturation", updated.saturation)
            .putFloat("pipeline_vibrance", updated.vibrance)
            .putFloat("pipeline_temperature", updated.temperature)
            .putFloat("pipeline_tint", updated.tint)
            .putFloat("pipeline_sharpness", updated.sharpness)
            .putFloat("pipeline_texture", updated.texture)
            .putFloat("pipeline_noise_reduction", updated.noiseReduction)
            .putFloat("pipeline_detail_preservation", updated.detailPreservation)
            .apply()
    }

    private fun read(): OppoPhotoSettings = OppoPhotoSettings(
        autoHdrEnabled = preferences.getBoolean("auto_hdr", true),
        fastShutterEnabled = preferences.getBoolean("fast_shutter", true),
        fastShutterFps = preferences.getInt("fast_shutter_fps", 20),
        refocusEnabled = preferences.getBoolean("refocus", false),
        refocusFrameCount = preferences.getInt("refocus_frame_count", 5),
        customPipelineEnabled = preferences.getBoolean("custom_pipeline", false),
        pipelineProfile = preferences.getString("pipeline_profile", "NATURAL") ?: "NATURAL",
        exposure = preferences.getFloat("pipeline_exposure", 0f),
        contrast = preferences.getFloat("pipeline_contrast", 1f),
        highlights = preferences.getFloat("pipeline_highlights", -15f),
        shadows = preferences.getFloat("pipeline_shadows", 12f),
        saturation = preferences.getFloat("pipeline_saturation", 0f),
        vibrance = preferences.getFloat("pipeline_vibrance", 5f),
        temperature = preferences.getFloat("pipeline_temperature", 0f),
        tint = preferences.getFloat("pipeline_tint", 0f),
        sharpness = preferences.getFloat("pipeline_sharpness", 18f),
        texture = preferences.getFloat("pipeline_texture", 0f),
        noiseReduction = preferences.getFloat("pipeline_noise_reduction", 10f),
        detailPreservation = preferences.getFloat("pipeline_detail_preservation", 88f),
    ).normalized()
}
