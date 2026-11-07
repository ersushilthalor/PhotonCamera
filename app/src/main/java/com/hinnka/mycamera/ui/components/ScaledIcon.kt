package com.hinnka.mycamera.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Draws an icon at 93.75% of its requested visual size (25% larger than the previous 75% scale) without changing its measured bounds.
 * This keeps surrounding touch targets and alignment unchanged.
 */
@Composable
fun ScaledIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer {
            scaleX = 0.9375f
            scaleY = 0.9375f
        },
        tint = tint
    )
}

@Composable
fun ScaledIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer {
            scaleX = 0.9375f
            scaleY = 0.9375f
        },
        tint = tint
    )
}

@Composable
fun ScaledIcon(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer {
            scaleX = 0.9375f
            scaleY = 0.9375f
        },
        tint = tint
    )
}
