package com.hinnka.mycamera.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.hinnka.mycamera.data.FloatingWindowAppearance

data class FloatingWindowBackdrop(
    val bitmap: Bitmap?,
    val previewBoundsInRoot: Rect?,
)

/** Draws the sampled camera preview and blurs that image only; the controls stay crisp. */
fun Modifier.floatingWindowMaterial(
    appearance: FloatingWindowAppearance,
    backdrop: FloatingWindowBackdrop?,
    shape: Shape = RoundedCornerShape(16.dp),
): Modifier = composed {
    val surfaceBoundsInRoot = remember { mutableStateOf<Rect?>(null) }
    val transparency = appearance.transparencyPercent.coerceIn(0, 100)
    val blurStrength = appearance.blurStrengthDp.coerceIn(0f, 50f)

    this
        .clip(shape)
        .onGloballyPositioned { surfaceBoundsInRoot.value = it.boundsInRoot() }
        .drawBehind {
            val surfaceBounds = surfaceBoundsInRoot.value
            val previewBounds = backdrop?.previewBoundsInRoot
            val bitmap = backdrop?.bitmap?.takeUnless { it.isRecycled }
            if (surfaceBounds != null && previewBounds != null && bitmap != null) {
                drawIntoCanvas { canvas ->
                    val destination = RectF(
                        previewBounds.left - surfaceBounds.left,
                        previewBounds.top - surfaceBounds.top,
                        previewBounds.right - surfaceBounds.left,
                        previewBounds.bottom - surfaceBounds.top,
                    )
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurStrength > 0f) {
                        val radius = (blurStrength * 2.2f).coerceIn(0f, 160f)
                        paint.setRenderEffect(
                            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                        )
                    }
                    canvas.nativeCanvas.drawBitmap(bitmap, null, destination, paint)
                }
            } else {
                drawRect(Color(0xFF151820))
            }
            drawRect(Color.Black.copy(alpha = 1f - transparency / 100f))
            drawRect(Color.White.copy(alpha = 0.035f))
        }
}
