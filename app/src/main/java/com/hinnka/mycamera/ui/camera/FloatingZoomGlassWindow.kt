package com.hinnka.mycamera.ui.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawImage
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import com.hinnka.mycamera.R
import com.hinnka.mycamera.ui.theme.AccentColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Uses the already-rendered GL viewfinder as its backdrop; no second camera stream is opened.
 * Sampling is throttled and only runs while this floating panel is visible.
 */
@Composable
internal fun FloatingZoomGlassWindow(
    previewView: CameraGLSurfaceView?,
    previewBounds: Rect?,
    zoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    blurStrength: Float,
    transparency: Float,
    onBlurStrengthChange: (Float) -> Unit,
    onTransparencyChange: (Float) -> Unit,
    onZoomPreviewChange: (Float) -> Unit,
    onZoomCommit: (Float) -> Unit,
    onClose: () -> Unit,
    zoomControls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewBitmap = rememberFloatingPreviewFrame(previewView)
    var windowBounds by remember { mutableStateOf<Rect?>(null) }
    var localBlur by remember(blurStrength) { mutableFloatStateOf(blurStrength.coerceIn(0f, 50f)) }
    var localTransparency by remember(transparency) {
        mutableFloatStateOf(transparency.coerceIn(0f, 1f))
    }
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = modifier
            .width(328.dp)
            .shadow(20.dp, shape)
            .clip(shape)
            .background(Color(0xFF10141D))
            .border(1.dp, Color.White.copy(alpha = 0.27f), shape)
            .onGloballyPositioned { windowBounds = it.boundsInRoot() }
    ) {
        val imageBitmap = remember(previewBitmap) {
            previewBitmap?.takeUnless { it.isRecycled }?.asImageBitmap()
        }
        if (imageBitmap != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(localBlur.dp)
                    .graphicsLayer { alpha = localTransparency }
            ) {
                val sourceBounds = previewBounds
                val targetBounds = windowBounds
                if (sourceBounds != null && targetBounds != null &&
                    sourceBounds.width > 0f && sourceBounds.height > 0f &&
                    targetBounds.width > 0f && targetBounds.height > 0f
                ) {
                    val left = maxOf(sourceBounds.left, targetBounds.left)
                    val top = maxOf(sourceBounds.top, targetBounds.top)
                    val right = minOf(sourceBounds.right, targetBounds.right)
                    val bottom = minOf(sourceBounds.bottom, targetBounds.bottom)
                    if (right > left && bottom > top) {
                        val srcLeft = ((left - sourceBounds.left) / sourceBounds.width * imageBitmap.width)
                            .toInt().coerceIn(0, imageBitmap.width - 1)
                        val srcTop = ((top - sourceBounds.top) / sourceBounds.height * imageBitmap.height)
                            .toInt().coerceIn(0, imageBitmap.height - 1)
                        val srcRight = ((right - sourceBounds.left) / sourceBounds.width * imageBitmap.width)
                            .toInt().coerceIn(srcLeft + 1, imageBitmap.width)
                        val srcBottom = ((bottom - sourceBounds.top) / sourceBounds.height * imageBitmap.height)
                            .toInt().coerceIn(srcTop + 1, imageBitmap.height)
                        val targetWidth = size.width.roundToInt().coerceAtLeast(1)
                        val targetHeight = size.height.roundToInt().coerceAtLeast(1)
                        val dstLeft = ((left - targetBounds.left) / targetBounds.width * size.width)
                            .roundToInt().coerceIn(0, targetWidth - 1)
                        val dstTop = ((top - targetBounds.top) / targetBounds.height * size.height)
                            .roundToInt().coerceIn(0, targetHeight - 1)
                        val dstRight = ((right - targetBounds.left) / targetBounds.width * size.width)
                            .roundToInt().coerceIn(dstLeft + 1, targetWidth)
                        val dstBottom = ((bottom - targetBounds.top) / targetBounds.height * size.height)
                            .roundToInt().coerceIn(dstTop + 1, targetHeight)
                        drawImage(
                            image = imageBitmap,
                            srcOffset = IntOffset(srcLeft, srcTop),
                            srcSize = IntSize(srcRight - srcLeft, srcBottom - srcTop),
                            dstOffset = IntOffset(dstLeft, dstTop),
                            dstSize = IntSize(dstRight - dstLeft, dstBottom - dstTop),
                            filterQuality = FilterQuality.Medium
                        )
                    }
                }
            }
        }

        // Lower transparency darkens the glass; higher transparency reveals more of the live preview.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF10141D).copy(alpha = (0.90f * (1f - localTransparency) + 0.035f)))
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.floating_window_controls),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close_floating_window),
                        tint = Color.White
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                contentAlignment = Alignment.Center
            ) {
                zoomControls()
            }

            val safeMin = minZoomRatio.coerceAtLeast(0.1f)
            val safeMax = maxZoomRatio.coerceAtLeast(safeMin + 0.01f)
            val zoomSpanLog = ln(safeMax / safeMin).coerceAtLeast(0.0001f)
            fun progressForZoom(ratio: Float): Float =
                (ln(ratio.coerceIn(safeMin, safeMax) / safeMin) / zoomSpanLog).coerceIn(0f, 1f)
            fun zoomForProgress(progress: Float): Float =
                safeMin * exp(zoomSpanLog * progress.coerceIn(0f, 1f))

            var zoomSliderProgress by remember(safeMin, safeMax) {
                mutableFloatStateOf(progressForZoom(zoomRatio))
            }
            var zoomSliderDragging by remember { mutableStateOf(false) }
            LaunchedEffect(zoomRatio, safeMin, safeMax, zoomSliderDragging) {
                if (!zoomSliderDragging) zoomSliderProgress = progressForZoom(zoomRatio)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.floating_window_zoom),
                    color = Color.White.copy(alpha = 0.90f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = String.format(java.util.Locale.US, "%.2fx", zoomForProgress(zoomSliderProgress)),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = zoomSliderProgress,
                onValueChange = { progress ->
                    zoomSliderDragging = true
                    zoomSliderProgress = progress
                    onZoomPreviewChange(zoomForProgress(progress))
                },
                onValueChangeFinished = {
                    zoomSliderDragging = false
                    onZoomCommit(zoomForProgress(zoomSliderProgress))
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = AccentColor,
                    activeTrackColor = AccentColor,
                    inactiveTrackColor = Color.White.copy(alpha = 0.28f)
                )
            )

            Text(
                text = stringResource(R.string.floating_window_blur, localBlur.roundToInt()),
                color = Color.White.copy(alpha = 0.90f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Slider(
                value = localBlur,
                onValueChange = { localBlur = it },
                onValueChangeFinished = { onBlurStrengthChange(localBlur) },
                valueRange = 0f..50f,
                colors = SliderDefaults.colors(
                    thumbColor = AccentColor,
                    activeTrackColor = AccentColor,
                    inactiveTrackColor = Color.White.copy(alpha = 0.28f)
                )
            )

            Text(
                text = stringResource(
                    R.string.floating_window_transparency,
                    (localTransparency * 100f).roundToInt()
                ),
                color = Color.White.copy(alpha = 0.90f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Slider(
                value = localTransparency,
                onValueChange = { localTransparency = it },
                onValueChangeFinished = { onTransparencyChange(localTransparency) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = AccentColor,
                    activeTrackColor = AccentColor,
                    inactiveTrackColor = Color.White.copy(alpha = 0.28f)
                )
            )
        }
    }
}

@Composable
private fun rememberFloatingPreviewFrame(previewView: CameraGLSurfaceView?): Bitmap? {
    val bitmapState = remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(previewView) {
        if (previewView == null) {
            bitmapState.value?.takeUnless { it.isRecycled }?.recycle()
            bitmapState.value = null
            return@LaunchedEffect
        }
        var retiredBitmap: Bitmap? = null
        try {
            while (isActive) {
                val captured = withTimeoutOrNull(900L) {
                    suspendCancellableCoroutine<Bitmap> { continuation ->
                        previewView.capturePreviewFrame(480) { bitmap ->
                            if (continuation.isActive) continuation.resume(bitmap)
                            else if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                }
                if (captured != null && !captured.isRecycled) {
                    retiredBitmap = bitmapState.value
                    bitmapState.value = captured
                    if (retiredBitmap != null) {
                        withFrameNanos { }
                        retiredBitmap?.takeUnless { it.isRecycled }?.recycle()
                        retiredBitmap = null
                    }
                }
                delay(180L)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            retiredBitmap?.takeUnless { it.isRecycled }?.recycle()
            bitmapState.value?.takeUnless { it.isRecycled }?.recycle()
            bitmapState.value = null
        }
    }
    return bitmapState.value
}
