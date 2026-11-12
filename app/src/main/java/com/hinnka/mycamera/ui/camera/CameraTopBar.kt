package com.hinnka.mycamera.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.ui.components.PhysicalButton
import com.hinnka.mycamera.ui.icons.AppIcons
import com.hinnka.mycamera.ui.theme.AccentColor
import com.hinnka.mycamera.video.CaptureMode
import com.hinnka.mycamera.video.VideoCapabilities
import com.hinnka.mycamera.video.VideoConfig
import com.hinnka.mycamera.video.VideoResolutionPreset

/**
 * Google Camera-inspired toolbar. The five hit targets stay the same size in Photo and Video:
 * scene/exposure, format, aspect ratio, flash, and settings.
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
        PhysicalButton(
            modifier = Modifier.size(36.dp),
            onClick = onHistogramToggle
        ) {
            SunburstIcon(
                tint = if (showHistogram) AccentColor else Color.White,
                contentDescription = stringResource(R.string.histogram)
            )
        }

        TopTextAction(
            text = aspectRatioLabel,
            contentDescription = stringResource(R.string.camera_aspect_ratio),
            onClick = onAspectRatioClick
        )

        TopIconAction(
            icon = AppIcons.Stabilization,
            contentDescription = stringResource(R.string.camera_level_indicator),
            tint = if (showLevelIndicator) AccentColor else Color.White,
            onClick = onLevelIndicatorToggle
        )

        TopIconAction(
            icon = when (flashMode) {
                1 -> AppIcons.FlashOn
                2 -> AppIcons.FlashlightOn
                else -> AppIcons.FlashOff
            },
            contentDescription = stringResource(R.string.flash),
            tint = Color.White,
            onClick = onFlashToggle
        )

        TopIconAction(
            icon = Icons.Default.Settings,
            contentDescription = stringResource(R.string.settings),
            tint = Color.White,
            onClick = onSettingsClick
        )
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
            PhysicalButton(
                modifier = Modifier.size(36.dp),
                onClick = onVideoHdrToggle
            ) {
                SunburstIcon(
                    tint = if (videoHdrEnabled) AccentColor else Color.White,
                    contentDescription = stringResource(R.string.camera_hdr)
                )
            }

            VideoFormatAction(
                videoConfig = videoConfig,
                onResolutionClick = onVideoResolutionClick,
                onFpsClick = onVideoFpsClick
            )

            TopTextAction(
                text = aspectRatioLabel,
                contentDescription = stringResource(R.string.camera_aspect_ratio),
                onClick = onAspectRatioClick
            )

            TopIconAction(
                icon = if (videoConfig.torchEnabled) AppIcons.FlashlightOn else AppIcons.FlashlightOff,
                contentDescription = stringResource(R.string.video_torch_chip),
                tint = if (videoCapabilities.supportsTorch) {
                    if (videoConfig.torchEnabled) AccentColor else Color.White
                } else {
                    Color.White.copy(alpha = 0.35f)
                },
                onClick = onVideoTorchToggle,
                enabled = videoCapabilities.supportsTorch
            )

            TopIconAction(
                icon = Icons.Default.Settings,
                contentDescription = stringResource(R.string.settings),
                tint = Color.White,
                onClick = onSettingsClick
            )
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
                .fillMaxWidth()
                .weight(1f)
                .clickable(onClick = onFpsClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = videoConfig.fps.fps.toString(),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TopIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    PhysicalButton(
        modifier = Modifier.size(36.dp),
        onClick = onClick,
        enabled = enabled
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = tint
        )
    }
}

@Composable
private fun TopTextAction(
    text: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    PhysicalButton(
        modifier = Modifier.size(36.dp),
        onClick = onClick
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun SunburstIcon(
    tint: Color,
    contentDescription: String
) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val strokeWidth = 1.7.dp.toPx()
        val coreRadius = size.minDimension * 0.17f
        val rayStart = size.minDimension * 0.34f
        val rayEnd = size.minDimension * 0.46f

        drawCircle(
            color = tint,
            radius = coreRadius,
            center = center,
            style = Stroke(width = strokeWidth)
        )
        repeat(8) { index ->
            val angle = Math.PI * index / 4.0
            val start = Offset(
                x = center.x + (rayStart * kotlin.math.cos(angle)).toFloat(),
                y = center.y + (rayStart * kotlin.math.sin(angle)).toFloat()
            )
            val end = Offset(
                x = center.x + (rayEnd * kotlin.math.cos(angle)).toFloat(),
                y = center.y + (rayEnd * kotlin.math.sin(angle)).toFloat()
            )
            drawLine(
                color = tint,
                start = start,
                end = end,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
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
