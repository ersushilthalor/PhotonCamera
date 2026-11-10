package com.hinnka.mycamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared material controls for camera floating surfaces. Blur is rendered by RenderEffect on the
 * Android render thread; opacity is applied independently so either control can be tuned live.
 */
@Immutable
data class FloatingUiMaterialSettings(
    val transparencyPercent: Int = 20,
    val blurIntensityPercent: Int = 100,
) {
    val surfaceAlpha: Float
        get() = 1f - transparencyPercent.coerceIn(0, 100) / 100f

    val blurRadius: Dp
        get() = (36f * blurIntensityPercent.coerceIn(0, 100) / 100f).dp
}

val LocalFloatingUiMaterialSettings = staticCompositionLocalOf { FloatingUiMaterialSettings() }
val LocalFloatingUiBackdrop = staticCompositionLocalOf<LiquidGlassBackdrop?> { null }

/**
 * A reusable camera control surface. When a backdrop source is available, its actual rendered
 * content is sampled by LiquidGlassSurface and blurred on the GPU; otherwise a translucent
 * material fallback is used.
 */
@Composable
fun FloatingUiMaterialSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    baseColor: Color = Color(0xFF1C1C1E),
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = LocalFloatingUiMaterialSettings.current
    val backdrop = LocalFloatingUiBackdrop.current
    val tint = baseColor.copy(alpha = (baseColor.alpha * settings.surfaceAlpha).coerceIn(0f, 1f))
    val shape = RoundedCornerShape(cornerRadius)

    if (backdrop != null && settings.blurIntensityPercent > 0) {
        LiquidGlassSurface(
            backdrop = backdrop,
            modifier = modifier.clip(shape),
            cornerRadius = cornerRadius,
            style = LiquidGlassStyle(
                blurRadius = settings.blurRadius,
                refractionHeight = 0.dp,
                refractionAmount = 0.dp,
                saturation = 1f,
                tint = tint,
                highlightWidth = 0.dp,
                highlightBlur = 0.dp,
                highlightAlpha = 0f,
                fallbackColor = tint,
            ),
            content = content,
        )
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .background(tint),
            content = content,
        )
    }
}
