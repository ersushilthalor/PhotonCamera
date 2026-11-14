package com.hinnka.mycamera.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LiquidGlassPipelineTier { AUTO, LEGACY, STANDARD, LIQUID }
enum class LiquidGlassAdaptiveStyle { STANDARD, SUBTLE }
enum class LiquidGlassSpecularStyle { DEFAULT, AMBIENT, PLAIN }

/**
 * Persistent settings for the live camera Liquid Glass renderer.
 *
 * Values and ranges mirror the PrismalAGSL playground controls. The rendering backend
 * is PhotonCamera's existing GLES preview compositor so the effect samples the real
 * camera texture rather than a separate Compose snapshot.
 */
data class LiquidGlassSettings(
    val enabled: Boolean = true,
    val pipeline: LiquidGlassPipelineTier = LiquidGlassPipelineTier.AUTO,
    val blurRadiusDp: Float = 8f,
    val refractionHeightDp: Float = 16f,
    val refractionAmountDp: Float = 32f,
    val brightness: Float = 0f,
    val saturation: Float = 1.5f,
    val chromaticAberration: Float = 0f,
    val cornerRadiusDp: Float = 22f,
    val depthEffect: Boolean = false,
    val adaptiveLuminance: Boolean = false,
    val adaptiveStyle: LiquidGlassAdaptiveStyle = LiquidGlassAdaptiveStyle.STANDARD,
    val useVibrancy: Boolean = true,
    val specularEnabled: Boolean = true,
    val specularAlpha: Float = 1f,
    val specularWidthDp: Float = 0.5f,
    val specularStyle: LiquidGlassSpecularStyle = LiquidGlassSpecularStyle.DEFAULT,
    val depthShadowEnabled: Boolean = true,
    val depthShadowRadiusDp: Float = 24f,
    val depthShadowAlpha: Float = 1f,
    val depthInsetEnabled: Boolean = false,
    val depthInsetRadiusDp: Float = 8f,
    val depthInsetAlpha: Float = 0.5f,
    val surfaceTintAlpha: Float = 0f,
    val tintAlpha: Float = 0.28f,
    val gradientBlurFadeEnd: Float = 0.8f,
    val gradientBottomWeight: Float = 1f,
) {
    fun sanitized(): LiquidGlassSettings = copy(
        blurRadiusDp = blurRadiusDp.coerceIn(0f, 24f),
        refractionHeightDp = refractionHeightDp.coerceIn(0f, 32f),
        refractionAmountDp = refractionAmountDp.coerceIn(0f, 60f),
        brightness = brightness.coerceIn(-0.3f, 0.5f),
        saturation = saturation.coerceIn(0.5f, 2.5f),
        chromaticAberration = chromaticAberration.coerceIn(0f, 1f),
        cornerRadiusDp = cornerRadiusDp.coerceIn(0f, 48f),
        specularAlpha = specularAlpha.coerceIn(0f, 1f),
        specularWidthDp = specularWidthDp.coerceIn(0.25f, 2f),
        depthShadowRadiusDp = depthShadowRadiusDp.coerceIn(0f, 32f),
        depthShadowAlpha = depthShadowAlpha.coerceIn(0f, 1f),
        depthInsetRadiusDp = depthInsetRadiusDp.coerceIn(0f, 24f),
        depthInsetAlpha = depthInsetAlpha.coerceIn(0f, 1f),
        surfaceTintAlpha = surfaceTintAlpha.coerceIn(0f, 0.6f),
        tintAlpha = tintAlpha.coerceIn(0.05f, 0.75f),
        gradientBlurFadeEnd = gradientBlurFadeEnd.coerceIn(0f, 1f),
        gradientBottomWeight = gradientBottomWeight.coerceIn(0f, 1f),
    )
}

object LiquidGlassSettingsStore {
    private const val PREFS = "liquid_glass_settings_v1"
    private val lock = Any()
    private val mutableSettings = MutableStateFlow(LiquidGlassSettings())
    private var loaded = false

    val settings: StateFlow<LiquidGlassSettings> = mutableSettings.asStateFlow()

    fun load(context: Context): LiquidGlassSettings = synchronized(lock) {
        if (!loaded) {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val defaults = LiquidGlassSettings()
            mutableSettings.value = LiquidGlassSettings(
                enabled = p.getBoolean("enabled", defaults.enabled),
                pipeline = LiquidGlassPipelineTier.entries.getOrElse(p.getInt("pipeline", defaults.pipeline.ordinal)) { defaults.pipeline },
                blurRadiusDp = p.getFloat("blurRadiusDp", defaults.blurRadiusDp),
                refractionHeightDp = p.getFloat("refractionHeightDp", defaults.refractionHeightDp),
                refractionAmountDp = p.getFloat("refractionAmountDp", defaults.refractionAmountDp),
                brightness = p.getFloat("brightness", defaults.brightness),
                saturation = p.getFloat("saturation", defaults.saturation),
                chromaticAberration = p.getFloat("chromaticAberration", defaults.chromaticAberration),
                cornerRadiusDp = p.getFloat("cornerRadiusDp", defaults.cornerRadiusDp),
                depthEffect = p.getBoolean("depthEffect", defaults.depthEffect),
                adaptiveLuminance = p.getBoolean("adaptiveLuminance", defaults.adaptiveLuminance),
                adaptiveStyle = LiquidGlassAdaptiveStyle.entries.getOrElse(p.getInt("adaptiveStyle", defaults.adaptiveStyle.ordinal)) { defaults.adaptiveStyle },
                useVibrancy = p.getBoolean("useVibrancy", defaults.useVibrancy),
                specularEnabled = p.getBoolean("specularEnabled", defaults.specularEnabled),
                specularAlpha = p.getFloat("specularAlpha", defaults.specularAlpha),
                specularWidthDp = p.getFloat("specularWidthDp", defaults.specularWidthDp),
                specularStyle = LiquidGlassSpecularStyle.entries.getOrElse(p.getInt("specularStyle", defaults.specularStyle.ordinal)) { defaults.specularStyle },
                depthShadowEnabled = p.getBoolean("depthShadowEnabled", defaults.depthShadowEnabled),
                depthShadowRadiusDp = p.getFloat("depthShadowRadiusDp", defaults.depthShadowRadiusDp),
                depthShadowAlpha = p.getFloat("depthShadowAlpha", defaults.depthShadowAlpha),
                depthInsetEnabled = p.getBoolean("depthInsetEnabled", defaults.depthInsetEnabled),
                depthInsetRadiusDp = p.getFloat("depthInsetRadiusDp", defaults.depthInsetRadiusDp),
                depthInsetAlpha = p.getFloat("depthInsetAlpha", defaults.depthInsetAlpha),
                surfaceTintAlpha = p.getFloat("surfaceTintAlpha", defaults.surfaceTintAlpha),
                tintAlpha = p.getFloat("tintAlpha", defaults.tintAlpha),
                gradientBlurFadeEnd = p.getFloat("gradientBlurFadeEnd", defaults.gradientBlurFadeEnd),
                gradientBottomWeight = p.getFloat("gradientBottomWeight", defaults.gradientBottomWeight),
            ).sanitized()
            loaded = true
        }
        mutableSettings.value
    }

    fun update(
        context: Context,
        transform: (LiquidGlassSettings) -> LiquidGlassSettings,
    ): LiquidGlassSettings = synchronized(lock) {
        val next = transform(load(context)).sanitized()
        mutableSettings.value = next
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", next.enabled)
            .putInt("pipeline", next.pipeline.ordinal)
            .putFloat("blurRadiusDp", next.blurRadiusDp)
            .putFloat("refractionHeightDp", next.refractionHeightDp)
            .putFloat("refractionAmountDp", next.refractionAmountDp)
            .putFloat("brightness", next.brightness)
            .putFloat("saturation", next.saturation)
            .putFloat("chromaticAberration", next.chromaticAberration)
            .putFloat("cornerRadiusDp", next.cornerRadiusDp)
            .putBoolean("depthEffect", next.depthEffect)
            .putBoolean("adaptiveLuminance", next.adaptiveLuminance)
            .putInt("adaptiveStyle", next.adaptiveStyle.ordinal)
            .putBoolean("useVibrancy", next.useVibrancy)
            .putBoolean("specularEnabled", next.specularEnabled)
            .putFloat("specularAlpha", next.specularAlpha)
            .putFloat("specularWidthDp", next.specularWidthDp)
            .putInt("specularStyle", next.specularStyle.ordinal)
            .putBoolean("depthShadowEnabled", next.depthShadowEnabled)
            .putFloat("depthShadowRadiusDp", next.depthShadowRadiusDp)
            .putFloat("depthShadowAlpha", next.depthShadowAlpha)
            .putBoolean("depthInsetEnabled", next.depthInsetEnabled)
            .putFloat("depthInsetRadiusDp", next.depthInsetRadiusDp)
            .putFloat("depthInsetAlpha", next.depthInsetAlpha)
            .putFloat("surfaceTintAlpha", next.surfaceTintAlpha)
            .putFloat("tintAlpha", next.tintAlpha)
            .putFloat("gradientBlurFadeEnd", next.gradientBlurFadeEnd)
            .putFloat("gradientBottomWeight", next.gradientBottomWeight)
            .apply()
        next
    }

    fun reset(context: Context): LiquidGlassSettings = update(context) { LiquidGlassSettings() }
}
