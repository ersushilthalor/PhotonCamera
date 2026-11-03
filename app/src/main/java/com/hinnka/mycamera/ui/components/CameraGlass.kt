/*
 * Liquid Glass controls adapted from the supplied VCamera implementation.
 * CameraGLSurfaceView is currently a GLSurfaceView and cannot be sampled by
 * Compose backdrop capture. Keep live blur/refraction disabled until the
 * preview renderer is safely made capturable; use the frosted fallback now.
 *
 * AndroidLiquidGlass: https://github.com/Kyant0/AndroidLiquidGlass
 * License: Apache-2.0
 */
package com.hinnka.mycamera.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Disabled until the camera preview is a capturable TextureView/Compose source.
 * Enabling backdrop capture over the current GLSurfaceView would show the
 * wrong pixels and risks interfering with the viewfinder.
 */
object GlassConfig {
    var enabled by mutableStateOf(false)
}

val LocalGlassBackdrop = compositionLocalOf<Backdrop?> { null }

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = RoundedCornerShape(28.dp),
    blurRadius: Dp = 10.dp,
    lensHeight: Dp = 12.dp,
    lensAmount: Dp = 24.dp,
    lensDepth: Boolean = false,
    tint: Color = Color.Black,
    tintAlpha: Float = 0.30f,
    frostedAlpha: Float = (tintAlpha + 0.30f).coerceAtMost(0.75f),
    dropShadow: Boolean = true,
    nestedGlass: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val backdrop = LocalGlassBackdrop.current
    if (backdrop == null || !GlassConfig.enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        Box(modifier.frostedFallback(shape, tint, frostedAlpha), content = content)
        return
    }

    val exported = if (nestedGlass) rememberLayerBackdrop() else null
    val glassModifier = if (dropShadow) {
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                if (blurRadius > 0.dp) blur(blurRadius.toPx())
                if (lensAmount > 0.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    lens(lensHeight.toPx(), lensAmount.toPx(), lensDepth)
                }
            },
            exportedBackdrop = exported,
            onDrawSurface = { drawGlassSurface(tint, tintAlpha) },
        )
    } else {
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            shadow = null,
            effects = {
                vibrancy()
                if (blurRadius > 0.dp) blur(blurRadius.toPx())
                if (lensAmount > 0.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    lens(lensHeight.toPx(), lensAmount.toPx(), lensDepth)
                }
            },
            exportedBackdrop = exported,
            onDrawSurface = { drawGlassSurface(tint, tintAlpha) },
        )
    }

    Box(glassModifier) {
        if (exported != null) {
            CompositionLocalProvider(LocalGlassBackdrop provides exported) { content() }
        } else {
            content()
        }
    }
}

private fun DrawScope.drawGlassSurface(tint: Color, tintAlpha: Float) {
    drawRect(tint.copy(alpha = tintAlpha))
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.10f),
            0.55f to Color.Transparent,
        ),
    )
}

fun Modifier.glassRim(shape: Shape, width: Dp = 0.75.dp): Modifier = border(
    width = width,
    brush = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.60f),
        0.30f to Color.White.copy(alpha = 0.10f),
        0.70f to Color.White.copy(alpha = 0.10f),
        1f to Color.White.copy(alpha = 0.45f),
    ),
    shape = shape,
)

private fun Modifier.frostedFallback(shape: Shape, tint: Color, alpha: Float): Modifier = this
    .clip(shape)
    .background(tint.copy(alpha = alpha))
    .background(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.14f),
            1f to Color.White.copy(alpha = 0.03f),
        ),
    )
    .glassRim(shape)

fun Modifier.glassTile(
    shape: Shape = RoundedCornerShape(20.dp),
    selected: Boolean = false,
    selectedColor: Color = Color(0xFFFFD60A),
): Modifier = this
    .clip(shape)
    .background(if (selected) selectedColor else Color.White.copy(alpha = 0.12f))
    .background(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (selected) 0.22f else 0.10f),
            0.6f to Color.Transparent,
        ),
    )
    .glassRim(shape)

@Composable
fun GlassTopPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 32.dp,
    blurRadius: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier.offset(y = -cornerRadius),
        shape = RoundedCornerShape(cornerRadius),
        blurRadius = blurRadius,
        lensHeight = cornerRadius * 0.55f,
        lensAmount = cornerRadius,
        lensDepth = true,
        tintAlpha = 0.42f,
        frostedAlpha = 0.58f,
    ) {
        Column(Modifier.padding(top = cornerRadius), content = content)
    }
}
