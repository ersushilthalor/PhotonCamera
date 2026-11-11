package com.hinnka.mycamera.ui.camera

import kotlin.math.exp
import kotlin.math.ln
import androidx.compose.animation.core.CubicBezierEasing
import kotlin.math.roundToInt

internal const val ZoomStopAnimationDurationMillis = 500
internal val ZoomStopAnimationEasing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)

internal fun interpolateZoomRatio(
    startZoom: Float,
    targetZoom: Float,
    progress: Float
): Float {
    require(startZoom.isFinite() && startZoom > 0f)
    require(targetZoom.isFinite() && targetZoom > 0f)
    val fraction = progress.coerceIn(0f, 1f)
    val startLog = ln(startZoom.toDouble())
    val targetLog = ln(targetZoom.toDouble())
    return exp(startLog + (targetLog - startLog) * fraction).toFloat()
}

internal fun resolveZoomStopAnimationDurationMillis(
    startZoom: Float,
    targetZoom: Float
): Int {
    require(startZoom.isFinite() && startZoom > 0f)
    require(targetZoom.isFinite() && targetZoom > 0f)
    return ZoomStopAnimationDurationMillis
}
