package com.hinnka.mycamera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.hinnka.mycamera.ui.icons.AppIcons
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
            Icon(
                imageVector = AppIcons.BarChart,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (showHistogram) AccentColor else Color.White
            )
        }

        AspectRatioAction(
            text = aspectRatioLabel,
            onClick = onAspectRatioClick
        )

        DrawingAction(
            contentDescription = stringResource(R.string.camera_level_indicator),
            onClick = onLevelIndicatorToggle
        ) {
            Icon(
                imageVector = AppIcons.Stabilization,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (showLevelIndicator) AccentColor else Color.White
            )
        }

        DrawingAction(
            contentDescription = stringResource(R.string.flash),
            onClick = onFlashToggle
        ) {
            Icon(
                imageVector = when (flashMode) {
                    0 -> AppIcons.FlashOff
                    1 -> AppIcons.FlashOn
                    2 -> AppIcons.FlashlightOn
                    else -> AppIcons.FlashOff
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
        }

        DrawingAction(
            contentDescription = stringResource(R.string.settings),
            onClick = onSettingsClick
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
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
                Icon(
                    imageVector = AppIcons.Contrast,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (videoHdrEnabled) AccentColor else Color.White
                )
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
                Icon(
                    imageVector = if (videoConfig.torchEnabled) {
                        AppIcons.FlashlightOn
                    } else {
                        AppIcons.FlashlightOff
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (videoCapabilities.supportsTorch && videoConfig.torchEnabled) {
                        AccentColor
                    } else {
                        Color.White
                    }
                )
            }

            DrawingAction(
                contentDescription = stringResource(R.string.settings),
                onClick = onSettingsClick
            ) {
                Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
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
        Column(
            modifier = Modifier.size(width = 31.dp, height = 25.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = AppIcons.Crop169,
                contentDescription = null,
                modifier = Modifier.size(width = 22.dp, height = 15.dp),
                tint = Color.White
            )
            Text(
                text = text,
                color = Color.White,
                fontSize = 8.sp,
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

private fun formatRecordingDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
