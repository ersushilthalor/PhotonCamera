package com.hinnka.mycamera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.dp

/**
 * Live viewfinder histogram.
 * The common floating surface owns its tint, blur and optional Liquid Glass material.
 */
@Composable
fun HistogramView(
    histogram: IntArray?,
    modifier: Modifier = Modifier,
    backdrop: LiquidGlassBackdrop? = null,
    appearance: FloatingWindowAppearance = FloatingWindowAppearance(),
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .width(120.dp)
            .height(30.dp)
            .clip(shape)
    ) {
        FloatingWindowSurface(
            backdrop = backdrop,
            modifier = Modifier.matchParentSize(),
            cornerRadius = 4.dp,
            appearance = appearance,
        )
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            if (histogram == null || histogram.isEmpty()) return@Canvas
            val maxCount = histogram.maxOrNull() ?: 1
            if (maxCount == 0) return@Canvas

            val width = size.width
            val height = size.height
            val stepX = width / 256f
            val path = Path().apply {
                moveTo(0f, height)
                for (i in histogram.indices) {
                    val x = i * stepX
                    val y = height - (histogram[i].toFloat() / maxCount * height)
                    lineTo(x, y)
                }
                lineTo(width, height)
                close()
            }
            drawPath(path = path, color = Color.White.copy(alpha = 0.8f), style = Fill)
        }
    }
}
