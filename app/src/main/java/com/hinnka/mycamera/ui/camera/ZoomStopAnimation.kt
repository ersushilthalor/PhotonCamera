package com.hinnka.mycamera.ui.camera

import kotlin.math.exp
import kotlin.math.ln

private const val ZoomStopAnimationDurationMillis = 500

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
