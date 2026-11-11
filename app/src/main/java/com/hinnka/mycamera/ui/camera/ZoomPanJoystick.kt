package com.hinnka.mycamera.ui.camera

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Compact analog joystick for moving a Camera2 digital-zoom crop.
 *
 * The thumb returns to the centre on release, while the crop position remains in
 * CameraState. The top-right grip moves the whole control around the viewfinder.
 */
@Composable
internal fun ZoomPanJoystick(
    modifier: Modifier = Modifier,
    onPanBy: (Float, Float) -> Unit,
    onResetPan: () -> Unit,
    onMoveBy: (Float, Float) -> Unit,
) {
    val maxTravelPx = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }

    val latestKnobOffset by rememberUpdatedState(knobOffset)
    val currentOnPanBy by rememberUpdatedState(onPanBy)
    val currentOnResetPan by rememberUpdatedState(onResetPan)
    val currentOnMoveBy by rememberUpdatedState(onMoveBy)
    val animatedKnobOffset by animateOffsetAsState(
        targetValue = knobOffset,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 650f),
        label = "zoomPanJoystickThumb",
    )

    // Frame-time based output keeps analog speed consistent on 60/90/120 Hz displays.
    // Maximum base speed is 0.36 normalized crop-travel units per second at 3x.
    LaunchedEffect(isDragging) {
        var previousFrameNanos = 0L
        while (isDragging) {
            withFrameNanos { frameNanos ->
                if (previousFrameNanos != 0L) {
                    val deltaSeconds = ((frameNanos - previousFrameNanos) / 1_000_000_000f)
                        .coerceIn(0f, 0.05f)
                    val x = (latestKnobOffset.x / maxTravelPx).coerceIn(-1f, 1f)
                    val y = (latestKnobOffset.y / maxTravelPx).coerceIn(-1f, 1f)
                    if (deltaSeconds > 0f && (x != 0f || y != 0f)) {
                        currentOnPanBy(x * 0.36f * deltaSeconds, y * 0.36f * deltaSeconds)
                    }
                }
                previousFrameNanos = frameNanos
            }
        }
    }

    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.58f))
            .border(1.dp, Color.White.copy(alpha = 0.82f), CircleShape)
            .pointerInput(maxTravelPx) {
                fun clampOffset(offset: Offset): Offset {
                    val distance = hypot(offset.x, offset.y)
                    if (distance <= maxTravelPx || distance == 0f) return offset
                    val scale = maxTravelPx / distance
                    return Offset(offset.x * scale, offset.y * scale)
                }

                detectDragGestures(
                    onDragStart = { start ->
                        knobOffset = clampOffset(start - Offset(size.width / 2f, size.height / 2f))
                        isDragging = true
                    },
                    onDragEnd = {
                        isDragging = false
                        knobOffset = Offset.Zero
                    },
                    onDragCancel = {
                        isDragging = false
                        knobOffset = Offset.Zero
                    },
                ) { change, dragAmount ->
                    change.consume()
                    knobOffset = clampOffset(knobOffset + dragAmount)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { currentOnResetPan() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "↕",
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 3.dp),
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 11.sp,
        )
        Text(
            "↔",
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 3.dp),
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 11.sp,
        )
        Text(
            "↔",
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 3.dp),
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 11.sp,
        )
        Box(
            modifier = Modifier
                .size(22.dp)
                .offset { IntOffset(animatedKnobOffset.x.roundToInt(), animatedKnobOffset.y.roundToInt()) }
                .border(1.5.dp, Color.White.copy(alpha = 0.96f), CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.94f), CircleShape),
        )

        // Move grip is a separate touch target, so positioning never changes the crop.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(19.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        currentOnMoveBy(dragAmount.x, dragAmount.y)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("⠿", color = Color.White, fontSize = 12.sp)
        }
    }
}
