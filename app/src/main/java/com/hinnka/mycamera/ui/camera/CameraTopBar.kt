package com.hinnka.mycamera.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.ui.components.PhysicalButton
import com.hinnka.mycamera.ui.theme.AccentColor
import com.hinnka.mycamera.video.CaptureMode
import com.hinnka.mycamera.video.VideoCapabilities
import com.hinnka.mycamera.video.VideoConfig
import com.hinnka.mycamera.video.VideoResolutionPreset

/**
 * Camera toolbar styled to match the compact outlined Google Camera controls.
 * Photo: brightness, aspect ratio, level, flash, settings.
 * Video: HDR, resolution/FPS, aspect ratio, torch, settings.
 */
@Composable
fun CameraTopBar(
    captureMode: CaptureMode,
    isRecording: Boolean,
    recordingElapsedMs: Long,
    flashMode: Int,
    onFlashToggle: () -> Unit,
    showHistogram: Boolean,
    onHistogramToggle: () -> Unit,
    showLevelIndicator: Boolean,
    onLevelIndicatorToggle: () -> Unit,
    aspectRatioLabel: String,
    onAspectRatioClick: () -> Unit,
    videoConfig: VideoConfig,
    videoCapabilities: VideoCapabilities,
    onVideoTorchToggle: () -> Unit,
    onVideoResolutionClick: () -> Unit,
    onVideoFpsClick: () -> Unit,
    videoHdrEnabled: Boolean,
    onVideoHdrToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (captureMode == CaptureMode.VIDEO) {
        VideoTopBar(
            isRecording = isRecording,
            recordingElapsedMs = recordingElapsedMs,
            videoConfig = videoConfig,
            videoCapabilities = videoCapabilities,
            aspectRatioLabel = aspectRatioLabel,
            onAspectRatioClick = onAspectRatioClick,
            onVideoTorchToggle = onVideoTorchToggle,
            onVideoResolutionClick = onVideoResolutionClick,
            onVideoFpsClick = onVideoFpsClick,
            videoHdrEnabled = videoHdrEnabled,
            onVideoHdrToggle = onVideoHdrToggle,
            onSettingsClick = onSettingsClick,
            modifier = modifier
        )
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 8.dp, start = 10.dp, end = 10.dp)
            .height(40.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DrawingAction(
            contentDescription = stringResource(R.string.histogram),
            onClick = onHistogramToggle
        ) {
            SunburstIcon(tint = if (showHistogram) AccentColor else Color.White)
        }

        AspectRatioAction(
            text = aspectRatioLabel,
            onClick = onAspectRatioClick
        )

        DrawingAction(
            contentDescription = stringResource(R.string.camera_level_indicator),
            onClick = onLevelIndicatorToggle
        ) {
            CompassIcon(tint = if (showLevelIndicator) AccentColor else Color.White)
        }

        DrawingAction(
            contentDescription = stringResource(R.string.flash),
            onClick = onFlashToggle
        ) {
            FlashIcon(tint = Color.White, enabled = flashMode != 0)
        }

        DrawingAction(
            contentDescription = stringResource(R.string.settings),
            onClick = onSettingsClick
        ) {
            GearIcon(tint = Color.White)
        }
    }
}

@Composable
private fun VideoTopBar(
    isRecording: Boolean,
    recordingElapsedMs: Long,
    videoConfig: VideoConfig,
    videoCapabilities: VideoCapabilities,
    aspectRatioLabel: String,
    onAspectRatioClick: () -> Unit,
    onVideoTorchToggle: () -> Unit,
    onVideoResolutionClick: () -> Unit,
    onVideoFpsClick: () -> Unit,
    videoHdrEnabled: Boolean,
    onVideoHdrToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 8.dp, start = 10.dp, end = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DrawingAction(
                contentDescription = stringResource(R.string.camera_hdr),
                onClick = onVideoHdrToggle
            ) {
                SunburstIcon(tint = if (videoHdrEnabled) AccentColor else Color.White)
            }

            VideoFormatAction(
                videoConfig = videoConfig,
                onResolutionClick = onVideoResolutionClick,
                onFpsClick = onVideoFpsClick
            )

            AspectRatioAction(
                text = aspectRatioLabel,
                onClick = onAspectRatioClick
            )

            DrawingAction(
                contentDescription = stringResource(R.string.video_torch_chip),
                onClick = onVideoTorchToggle,
                enabled = videoCapabilities.supportsTorch
            ) {
                FlashIcon(
                    tint = if (videoCapabilities.supportsTorch && videoConfig.torchEnabled) {
                        AccentColor
                    } else {
                        Color.White
                    },
                    enabled = videoConfig.torchEnabled
                )
            }

            DrawingAction(
                contentDescription = stringResource(R.string.settings),
                onClick = onSettingsClick
            ) {
                GearIcon(tint = Color.White)
            }
        }

        if (isRecording) {
            Text(
                text = formatRecordingDuration(recordingElapsedMs),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 2.dp),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun DrawingAction(
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    PhysicalButton(
        modifier = Modifier
            .size(40.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        onClick = onClick,
        enabled = enabled
    ) {
        Box(
            modifier = Modifier.size(22.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun AspectRatioAction(
    text: String,
    onClick: () -> Unit
) {
    PhysicalButton(
        modifier = Modifier
            .size(width = 44.dp, height = 40.dp)
            .semantics {
                contentDescription = text
                role = Role.Button
            },
        onClick = onClick
    ) {
        Box(
            modifier = Modifier.size(width = 31.dp, height = 25.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 1.45.dp.toPx()
                val edge = 1.dp.toPx()
                val corner = 5.dp.toPx()
                val right = size.width - edge
                val bottom = size.height - edge
                drawLine(Color.White, Offset(edge, edge), Offset(edge + corner, edge), stroke)
                drawLine(Color.White, Offset(edge, edge), Offset(edge, edge + corner), stroke)
                drawLine(Color.White, Offset(right - corner, edge), Offset(right, edge), stroke)
                drawLine(Color.White, Offset(right, edge), Offset(right, edge + corner), stroke)
                drawLine(Color.White, Offset(edge, bottom - corner), Offset(edge, bottom), stroke)
                drawLine(Color.White, Offset(edge, bottom), Offset(edge + corner, bottom), stroke)
                drawLine(Color.White, Offset(right - corner, bottom), Offset(right, bottom), stroke)
                drawLine(Color.White, Offset(right, bottom - corner), Offset(right, bottom), stroke)
            }
            Text(
                text = text,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun VideoFormatAction(
    videoConfig: VideoConfig,
    onResolutionClick: () -> Unit,
    onFpsClick: () -> Unit
) {
    Column(
        modifier = Modifier.size(width = 44.dp, height = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val resolutionLabel = when (videoConfig.resolution) {
            VideoResolutionPreset.UHD_2160P -> "4K"
            VideoResolutionPreset.FHD_1080P -> "FHD"
            VideoResolutionPreset.HD_720P -> "HD"
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(3.dp))
                .clickable(onClick = onResolutionClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = resolutionLabel,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .clickable(onClick = onFpsClick)
                .background(Color.White, RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = videoConfig.fps.fps.toString(),
                color = Color.Black,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 9.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SunburstIcon(tint: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = 1.7.dp.toPx()
        val rayStart = size.minDimension * 0.29f
        val rayEnd = size.minDimension * 0.48f
        repeat(8) { index ->
            val angle = Math.PI * index / 4.0
            drawLine(
                color = tint,
                start = Offset(
                    center.x + (rayStart * kotlin.math.cos(angle)).toFloat(),
                    center.y + (rayStart * kotlin.math.sin(angle)).toFloat()
                ),
                end = Offset(
                    center.x + (rayEnd * kotlin.math.cos(angle)).toFloat(),
                    center.y + (rayEnd * kotlin.math.sin(angle)).toFloat()
                ),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
        drawCircle(tint, radius = size.minDimension * 0.085f, center = center)
    }
}

@Composable
private fun CompassIcon(tint: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = 1.6.dp.toPx()
        val radius = size.minDimension * 0.39f
        drawCircle(tint, radius = radius, center = center, style = Stroke(stroke))
        val needle = Path().apply {
            moveTo(center.x, center.y - radius * 0.72f)
            lineTo(center.x + radius * 0.38f, center.y + radius * 0.30f)
            lineTo(center.x - radius * 0.10f, center.y + radius * 0.08f)
            close()
        }
        drawPath(needle, color = tint)
        drawCircle(Color.Black, radius = radius * 0.13f, center = center)
    }
}

@Composable
private fun FlashIcon(
    tint: Color,
    enabled: Boolean
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        val bolt = Path().apply {
            moveTo(w * 0.61f, h * 0.05f)
            lineTo(w * 0.34f, h * 0.49f)
            lineTo(w * 0.51f, h * 0.49f)
            lineTo(w * 0.40f, h * 0.94f)
            lineTo(w * 0.75f, h * 0.40f)
            lineTo(w * 0.56f, h * 0.40f)
            close()
        }
        drawPath(
            path = bolt,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        if (!enabled) {
            drawLine(
                color = tint,
                start = Offset(w * 0.12f, h * 0.15f),
                end = Offset(w * 0.88f, h * 0.85f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun GearIcon(tint: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = 1.65.dp.toPx()
        val outerRadius = size.minDimension * 0.36f
        val toothInner = size.minDimension * 0.39f
        val toothOuter = size.minDimension * 0.48f
        drawCircle(tint, radius = outerRadius, center = center, style = Stroke(stroke))
        drawCircle(
            tint,
            radius = size.minDimension * 0.14f,
            center = center,
            style = Stroke(stroke)
        )
        repeat(8) { index ->
            val angle = Math.PI * index / 4.0
            drawLine(
                color = tint,
                start = Offset(
                    center.x + (toothInner * kotlin.math.cos(angle)).toFloat(),
                    center.y + (toothInner * kotlin.math.sin(angle)).toFloat()
                ),
                end = Offset(
                    center.x + (toothOuter * kotlin.math.cos(angle)).toFloat(),
                    center.y + (toothOuter * kotlin.math.sin(angle)).toFloat()
                ),
                strokeWidth = stroke,
                cap = StrokeCap.Square
            )
        }
    }
}

private fun formatRecordingDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
