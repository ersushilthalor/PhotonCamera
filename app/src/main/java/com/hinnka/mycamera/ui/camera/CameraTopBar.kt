package com.hinnka.mycamera.ui.camera

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.hinnka.mycamera.ui.theme.AccentColor
import com.hinnka.mycamera.R
import com.hinnka.mycamera.video.CaptureMode
import com.hinnka.mycamera.video.VideoAspectRatio
import com.hinnka.mycamera.video.VideoCapabilities
import com.hinnka.mycamera.video.VideoConfig
import com.hinnka.mycamera.video.VideoLogProfile
import com.hinnka.mycamera.video.VideoResolutionPreset
import com.hinnka.mycamera.video.VideoStabilizationMode
import com.hinnka.mycamera.ui.icons.AppIcons

@Composable
fun CameraTopBar(
    captureMode: CaptureMode,
    isRecording: Boolean,
    recordingElapsedMs: Long,
    flashMode: Int,
    onFlashToggle: () -> Unit,
    useLivePhoto: Boolean,
    onLivePhotoToggle: () -> Unit,
    useRaw: Boolean,
    rawSupported: Boolean,
    onRawToggle: () -> Unit,
    videoConfig: VideoConfig,
    videoCapabilities: VideoCapabilities,
    onVideoTorchToggle: () -> Unit,
    onVideoStabilizationModeChange: (VideoStabilizationMode) -> Unit,
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
            onVideoTorchToggle = onVideoTorchToggle,
            onVideoStabilizationModeChange = onVideoStabilizationModeChange,
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
            .padding(top = 32.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The left capsule is a single RAW/JPEG toggle. The small numeric label
        // mirrors the compact 48 MP indicator in the reference camera UI.
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.13f))
                .clickable(enabled = rawSupported, onClick = onRawToggle)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    if (useRaw && rawSupported) R.string.gallery_raw_badge
                    else R.string.camera_capture_format_jpeg
                ),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = stringResource(R.string.camera_capture_resolution_48),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // One continuous capsule holds Flash, Motion Photo and the six-dot menu.
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.13f))
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopBarActionButton(
                onClick = onFlashToggle,
                contentDescription = stringResource(R.string.flash)
            ) {
                Icon(
                    imageVector = when (flashMode) {
                        0 -> AppIcons.FlashOff
                        1 -> AppIcons.FlashOn
                        2 -> AppIcons.FlashlightOn
                        else -> AppIcons.FlashOff
                    },
                    modifier = Modifier.size(20.dp).autoRotate(),
                    contentDescription = null,
                    tint = Color.White
                )
            }

            TopBarActionButton(
                onClick = onLivePhotoToggle,
                contentDescription = stringResource(R.string.settings_use_live_photo)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_live_photo),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).autoRotate(),
                    tint = if (useLivePhoto) AccentColor else Color.White
                )
                if (!useLivePhoto) {
                    Canvas(modifier = Modifier.size(20.dp)) {
                        drawLine(
                            color = Color.White,
                            start = Offset(size.width * 0.22f, size.height * 0.22f),
                            end = Offset(size.width * 0.78f, size.height * 0.78f),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            TopBarActionButton(
                onClick = onSettingsClick,
                contentDescription = stringResource(R.string.settings)
            ) {
                SixDotMenuIcon()
            }
        }
    }
}


@Composable
private fun TopBarActionButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .autoRotate(),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun SixDotMenuIcon() {
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoTopBar(
    isRecording: Boolean,
    recordingElapsedMs: Long,
    videoConfig: VideoConfig,
    videoCapabilities: VideoCapabilities,
    onVideoTorchToggle: () -> Unit,
    onVideoStabilizationModeChange: (VideoStabilizationMode) -> Unit,
    onVideoResolutionClick: () -> Unit,
    onVideoFpsClick: () -> Unit,
    videoHdrEnabled: Boolean,
    onVideoHdrToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var stabilizationMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, start = 8.dp, end = 8.dp)
            .height(56.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (isRecording) {
                RecordingHud(
                    elapsedMs = recordingElapsedMs,
                    videoConfig = videoConfig
                )
            } else {
                VideoParameterCluster(
                    videoConfig = videoConfig,
                    onResolutionClick = onVideoResolutionClick,
                    onFpsClick = onVideoFpsClick
                )
            }

            // Keep live controls available while recording. HDR changes the active
            // recording color layer; torch updates the Camera2 repeating request.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!videoConfig.logProfile.isEnabled) {
                    IconButton(onClick = onVideoHdrToggle) {
                        Text(
                            text = "HDR",
                            color = Color.White.copy(alpha = if (videoHdrEnabled) 1f else 0.65f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.autoRotate()
                        )
                    }
                }

                VideoActionIcon(
                    icon = if (videoConfig.torchEnabled) AppIcons.FlashlightOn else AppIcons.FlashlightOff,
                    active = videoConfig.torchEnabled,
                    enabled = videoCapabilities.supportsTorch,
                    onClick = onVideoTorchToggle,
                    contentDescription = stringResource(R.string.video_torch_chip)
                )

                if (!isRecording && videoCapabilities.availableStabilizationModes.size > 1) {
                    Box {
                        VideoActionIcon(
                            icon = AppIcons.Stabilization,
                            active = videoConfig.stabilizationMode != VideoStabilizationMode.OFF,
                            enabled = true,
                            onClick = { stabilizationMenuExpanded = true },
                            contentDescription = stringResource(R.string.settings_video_stabilization_default)
                        )
                        DropdownMenu(
                            expanded = stabilizationMenuExpanded,
                            onDismissRequest = { stabilizationMenuExpanded = false }
                        ) {
                            videoCapabilities.availableStabilizationModes.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(videoStabilizationModeLabel(mode)) },
                                    onClick = {
                                        stabilizationMenuExpanded = false
                                        onVideoStabilizationModeChange(mode)
                                    },
                                    trailingIcon = if (mode == videoConfig.stabilizationMode) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null
                                            )
                                        }
                                    } else null
                                )
                            }
                        }
                    }
                }

                if (!isRecording) {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                            modifier = Modifier.size(20.dp).autoRotate(),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoParameterCluster(
    videoConfig: VideoConfig,
    onResolutionClick: () -> Unit,
    onFpsClick: () -> Unit
) {
    Surface(
        color = Color.Black.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isOpenGate = videoConfig.aspectRatio == VideoAspectRatio.OPEN_GATE
            val resLabel = if (isOpenGate) {
                stringResource(R.string.video_resolution_max)
            } else when (videoConfig.resolution) {
                VideoResolutionPreset.UHD_2160P -> "4K"
                VideoResolutionPreset.FHD_1080P -> "1080p"
                VideoResolutionPreset.HD_720P -> "720p"
            }
            ParameterText(resLabel, onResolutionClick, enabled = !isOpenGate)

            ParameterDivider()

            ParameterText("${videoConfig.fps.fps} fps", onFpsClick)
        }
    }
}

@Composable
private fun ParameterText(
    text: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    enabled: Boolean = true
) {
    Text(
        text = text,
        color = if (enabled) tint else tint.copy(alpha = 0.4f),
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.sp
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun ParameterDivider() {
    Box(
        modifier = Modifier
            .size(1.dp, 12.dp)
            .background(Color.White.copy(alpha = 0.2f))
    )
}

@Composable
private fun VideoActionIcon(
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String
) {
    IconButton(
        onClick = onClick,
        enabled = enabled
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp).autoRotate(),
            tint = if (enabled) {
                if (active) AccentColor else Color.White
            } else {
                Color.White.copy(alpha = 0.3f)
            }
        )
    }
}

@Composable
private fun videoStabilizationModeLabel(mode: VideoStabilizationMode): String {
    return when (mode) {
        VideoStabilizationMode.OFF ->
            stringResource(R.string.settings_video_stabilization_mode_off)
        VideoStabilizationMode.EIS ->
            stringResource(R.string.settings_video_stabilization_mode_eis)
        VideoStabilizationMode.OIS ->
            stringResource(R.string.settings_video_stabilization_mode_ois)
        VideoStabilizationMode.ENHANCED ->
            stringResource(R.string.settings_video_stabilization_mode_enhanced)
    }
}

@Composable
private fun RecordingHud(
    elapsedMs: Long,
    videoConfig: VideoConfig,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recBlink")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recDotAlpha"
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.5f),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.Red.copy(alpha = dotAlpha))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatRecordingDuration(elapsedMs),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                )
            }
        }

        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (videoConfig.aspectRatio == VideoAspectRatio.OPEN_GATE) {
                    stringResource(R.string.video_resolution_max)
                } else {
                    videoConfig.resolution.displayName
                },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            Box(Modifier.size(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.4f)))
            Text(
                text = "${videoConfig.fps.fps} FPS",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            if (videoConfig.logProfile != VideoLogProfile.OFF) {
                Box(Modifier.size(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.4f)))
                Text(
                    text = "LOG",
                    color = AccentColor.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatRecordingDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
