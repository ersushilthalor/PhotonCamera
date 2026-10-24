package com.hinnka.mycamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Appearance settings shared by floating viewfinder tools.
 *
 * Transparency is expressed as the amount of the backdrop that remains visible (0 = opaque).
 * Liquid Glass intentionally disables blur and uses a low-opacity tint so the optical lens
 * distortion, rather than a frosted surface, remains the dominant effect.
 */
@Immutable
data class FloatingWindowAppearance(
    val transparency: Float = 0.6f,
    val blurRadius: Float = 0f,
    val liquidGlassEnabled: Boolean = false,
)

@Composable
fun FloatingWindowSurface(
    backdrop: LiquidGlassBackdrop?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    appearance: FloatingWindowAppearance = FloatingWindowAppearance(),
    content: @Composable BoxScope.() -> Unit = {},
) {
    val transparency = appearance.transparency.coerceIn(0f, 0.95f)
    val surfaceOpacity = if (appearance.liquidGlassEnabled) {
        (1f - transparency) * 0.12f
    } else {
        1f - transparency
    }
    val shapeModifier = modifier.clip(RoundedCornerShape(cornerRadius))
    if (backdrop == null) {
        Box(
            modifier = shapeModifier.background(Color.Black.copy(alpha = surfaceOpacity)),
            content = content,
        )
        return
    }

    val style = LiquidGlassStyle(
        blurRadius = if (appearance.liquidGlassEnabled) 0.dp else appearance.blurRadius.coerceIn(0f, 24f).dp,
        refractionHeight = 24.dp,
        refractionAmount = if (appearance.liquidGlassEnabled) 36.dp else 0.dp,
        saturation = if (appearance.liquidGlassEnabled) 1.5f else 1f,
        tint = Color.Black.copy(alpha = surfaceOpacity),
        highlightWidth = if (appearance.liquidGlassEnabled) 0.5.dp else 0.dp,
        highlightBlur = if (appearance.liquidGlassEnabled) 0.25.dp else 0.dp,
        highlightAlpha = if (appearance.liquidGlassEnabled) 0.5f else 0f,
        fallbackColor = Color.Black.copy(alpha = surfaceOpacity),
    )
    LiquidGlassSurface(
        backdrop = backdrop,
        modifier = shapeModifier,
        cornerRadius = cornerRadius,
        style = style,
        content = content,
    )
}
