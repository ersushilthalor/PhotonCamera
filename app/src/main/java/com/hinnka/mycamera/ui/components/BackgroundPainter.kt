package com.hinnka.mycamera.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.hinnka.mycamera.R
import com.hinnka.mycamera.viewmodel.CameraViewModel

/** App-wide switch for the Liquid Glass material, provided above the navigation host. */
val LocalLiquidGlassEnabled = compositionLocalOf { false }

/**
 * Reusable Liquid Glass surface with a cool translucent tint, specular edge and soft depth.
 * When the mode is off, the supplied legacy background is preserved.
 */
fun Modifier.liquidGlassSurface(
    shape: Shape,
    fallbackColor: Color,
): Modifier = composed {
    if (LocalLiquidGlassEnabled.current) {
        this
            .shadow(
                elevation = 18.dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0xFFB8D8FF).copy(alpha = 0.20f),
                spotColor = Color.Black.copy(alpha = 0.34f),
            )
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.30f),
                        Color(0xFFD8E9FF).copy(alpha = 0.17f),
                        Color(0xFF91B9E9).copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.07f),
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.68f),
                        Color(0xFFD9E9FF).copy(alpha = 0.24f),
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.42f),
                    )
                ),
                shape = shape,
            )
    } else if (fallbackColor == Color.Transparent) {
        this
    } else {
        this.clip(shape).background(fallbackColor)
    }
}

@Composable
fun rememberBackgroundPainter(viewModel: CameraViewModel): Painter {
    val bgImage by viewModel.backgroundImage.collectAsState()
    val context = LocalContext.current
    
    return remember(bgImage) {
        if (bgImage.startsWith("/")) {
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(bgImage)
                if (bitmap != null) {
                    BitmapPainter(bitmap.asImageBitmap())
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        } else {
            val resId = context.resources.getIdentifier(bgImage, "drawable", context.packageName)
            if (resId != 0) {
                // We can't use painterResource inside remember like this if it's not a composable call
                // But we can get the resource ID and use it outside.
                null // Will handle below
            } else {
                null
            }
        }
    } ?: run {
        // Fallback or Resource handling
        if (!bgImage.startsWith("/")) {
            val resId = context.resources.getIdentifier(bgImage, "drawable", context.packageName)
            if (resId != 0) {
                painterResource(resId)
            } else {
                painterResource(R.drawable.camera_bg)
            }
        } else {
            painterResource(R.drawable.camera_bg)
        }
    }
}


@Composable
fun AppBackground(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    transparency: Float? = null,
    blurRadius: Float? = null,
) {
    val userPreferences by viewModel.userPreferences.collectAsState()
    // Liquid Glass replaces the manual background opacity/blur values while enabled.
    val effectiveTransparency = if (userPreferences.liquidGlassEnabled) {
        0f
    } else {
        (transparency ?: userPreferences.backgroundTransparency)
            .takeIf { it.isFinite() }
            ?.coerceIn(0f, 100f)
            ?: 0f
    }
    val effectiveBlur = if (userPreferences.liquidGlassEnabled) {
        18f
    } else {
        (blurRadius ?: userPreferences.backgroundBlur)
            .takeIf { it.isFinite() }
            ?.coerceIn(0f, 30f)
            ?: 0f
    }

    Image(
        painter = rememberBackgroundPainter(viewModel),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = 1f - effectiveTransparency / 100f,
        modifier = modifier.blur(effectiveBlur.dp),
    )
}
