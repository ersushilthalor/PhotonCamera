package com.hinnka.mycamera.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hinnka.mycamera.data.LiquidGlassAdaptiveStyle
import com.hinnka.mycamera.data.LiquidGlassSettings
import com.hinnka.mycamera.data.LiquidGlassSpecularStyle
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.depth.PrismalDepthInset
import com.styropyr0.prismal.depth.PrismalDepthShadow
import com.styropyr0.prismal.effects.PrismalAdaptiveTuning
import com.styropyr0.prismal.effects.applyPrismalGlassEffects
import com.styropyr0.prismal.shapes.PrismalRoundedRectangle
import com.styropyr0.prismal.specular.PrismalSpecular
import com.styropyr0.prismal.specular.PrismalSpecularStyle

/**
 * The same single-surface material used by PrismalAGSL's Playground TunableGlassPanel.
 *
 * The Playground panel leaves its tint color unspecified, so tintAlpha alone must not
 * introduce the old blue wash. The optional white surface tint remains configurable.
 */
@Composable
internal fun CameraPrismalGlassSurface(
    backdrop: PrismalBackdrop,
    settings: LiquidGlassSettings,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val shape: () -> Shape = { PrismalRoundedRectangle(settings.cornerRadiusDp.dp) }
    val adaptiveTuning = when (settings.adaptiveStyle) {
        LiquidGlassAdaptiveStyle.STANDARD -> PrismalAdaptiveTuning.Standard
        LiquidGlassAdaptiveStyle.SUBTLE -> PrismalAdaptiveTuning.Subtle
    }

    val specularStyle = when (settings.specularStyle) {
        LiquidGlassSpecularStyle.DEFAULT -> PrismalSpecularStyle.Default
        LiquidGlassSpecularStyle.AMBIENT ->
            PrismalSpecularStyle.Ambient(intensity = settings.specularAlpha)
        LiquidGlassSpecularStyle.PLAIN ->
            PrismalSpecularStyle.Plain(
                color = Color.White.copy(alpha = 0.38f * settings.specularAlpha)
            )
    }
    val specular = PrismalSpecular(
        width = settings.specularWidthDp.dp,
        blurRadius = (settings.specularWidthDp / 2f).dp,
        alpha = settings.specularAlpha,
        style = specularStyle,
    )
    val depthShadow = PrismalDepthShadow(
        radius = settings.depthShadowRadiusDp.dp,
        alpha = settings.depthShadowAlpha,
    )
    val depthInset = PrismalDepthInset(
        radius = settings.depthInsetRadiusDp.dp,
        alpha = settings.depthInsetAlpha,
    )

    val specularProvider: (() -> PrismalSpecular?)? =
        if (settings.specularEnabled) ({ specular }) else null
    val depthShadowProvider: (() -> PrismalDepthShadow?)? =
        if (settings.depthShadowEnabled) ({ depthShadow }) else null
    val depthInsetProvider: (() -> PrismalDepthInset?)? =
        if (settings.depthInsetEnabled) ({ depthInset }) else null

    val glassModifier = if (settings.enabled) {
        Modifier.drawPrismalGlass(
            backdrop = backdrop,
            shape = shape,
            effects = {
                applyPrismalGlassEffects(
                    density = density,
                    adaptiveLuminance = settings.adaptiveLuminance,
                    luminance = 0.5f,
                    blurRadiusPx = with(density) { settings.blurRadiusDp.dp.toPx() },
                    refractionHeightPx = with(density) { settings.refractionHeightDp.dp.toPx() },
                    refractionAmountPx = with(density) { settings.refractionAmountDp.dp.toPx() },
                    brightness = settings.brightness,
                    saturation = settings.saturation,
                    depthEffect = settings.depthEffect,
                    chromaticAberration = settings.chromaticAberration,
                    useVibrancy = settings.useVibrancy,
                    adaptiveTuning = adaptiveTuning,
                )
            },
            specular = specularProvider,
            depthShadow = depthShadowProvider,
            depthInset = depthInsetProvider,
            onDrawSurface = {
                if (settings.surfaceTintAlpha > 0f) {
                    drawRect(Color.White.copy(alpha = settings.surfaceTintAlpha))
                }
            },
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier.then(glassModifier),
        content = content,
    )
}
