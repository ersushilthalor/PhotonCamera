package com.hinnka.mycamera.ui.camera

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

// Keep snapshot motion responsive without imposing a 220 ms minimum wait.
private const val MinZoomStopAnimationDurationMillis = 0
private const val MaxZoomStopAnimationDurationMillis = 180
private const val ZoomStopAnimationMillisPerOctave = 40f

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
    val octaveDistance = abs(ln((targetZoom / startZoom).toDouble()) / ln(2.0))
    return (MinZoomStopAnimationDurationMillis +
        octaveDistance * ZoomStopAnimationMillisPerOctave)
        .roundToInt()
        .coerceIn(
            MinZoomStopAnimationDurationMillis,
            MaxZoomStopAnimationDurationMillis
        )
}
