package com.hinnka.mycamera.ui.camera

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.video.VideoFpsPreset
import com.hinnka.mycamera.video.VideoResolutionPreset

private val ResolutionPanelShape = RoundedCornerShape(26.dp)
private val ResolutionPanelAccent = Color(0xFFFFC107)

@Composable
internal fun VideoResolutionFloatingPanel(
    isOpen: Boolean,
    selectedResolution: VideoResolutionPreset,
    selectedFps: VideoFpsPreset,
    availableResolutions: List<VideoResolutionPreset>,
    availableFps: List<VideoFpsPreset>,
    backdrop: Bitmap?,
    onResolutionSelected: (VideoResolutionPreset) -> Unit,
    onFpsSelected: (VideoFpsPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    val resolutionOptions = remember(availableResolutions, selectedResolution) {
        availableResolutions.distinct().ifEmpty { listOf(selectedResolution) }
    }
    val fpsOptions = remember(availableFps, selectedFps) {
        availableFps.distinct().ifEmpty { listOf(selectedFps) }
    }
    val backdropImage = remember(backdrop) {
        backdrop?.takeUnless(Bitmap::isRecycled)?.asImageBitmap()
    }

    AnimatedVisibility(
        visible = isOpen,
        modifier = modifier,
        enter = fadeIn(tween(170)) +
            scaleIn(
                initialScale = 0.78f,
                animationSpec = spring(dampingRatio = 0.52f, stiffness = 430f)
            ) +
            expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = spring(dampingRatio = 0.68f, stiffness = 380f)
            ) +
            slideInVertically(
                initialOffsetY = { -it / 5 },
                animationSpec = spring(dampingRatio = 0.68f, stiffness = 380f)
            ),
        exit = fadeOut(tween(120)) +
            scaleOut(targetScale = 0.94f, animationSpec = tween(120)) +
            shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(130)
            )
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 340.dp)
                .fillMaxWidth(0.92f)
                .shadow(18.dp, ResolutionPanelShape)
                .clip(ResolutionPanelShape)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.28f),
                            Color.White.copy(alpha = 0.11f),
                            Color.White.copy(alpha = 0.06f)
                        )
                    ),
                    shape = ResolutionPanelShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
                .testTag("floating_video_settings_panel")
        ) {
            if (backdropImage != null) {
                Image(
                    bitmap = backdropImage,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .blur(22.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0xFF171B25))
                )
            }

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xC91B202B),
                                Color(0xE60D111A)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                SelectorRow(label = "Size") {
                    resolutionOptions.forEach { option ->
                        val selected = selectedResolution == option
                        val label = when (option) {
                            VideoResolutionPreset.UHD_2160P -> "4K"
                            VideoResolutionPreset.FHD_1080P -> "1080p"
                            VideoResolutionPreset.HD_720P -> "720p"
                        }
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (selected) ResolutionPanelAccent else Color.White.copy(alpha = 0.94f),
                                fontSize = 14.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                letterSpacing = 0.2.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) ResolutionPanelAccent.copy(alpha = 0.13f)
                                        else Color.Transparent
                                    )
                                    .clickable { onResolutionSelected(option) }
                                    .padding(horizontal = 6.dp, vertical = 5.dp)
                                    .testTag("video_resolution_option_${option.name}")
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                SelectorRow(label = "FPS") {
                    fpsOptions.forEach { option ->
                        val selected = selectedFps == option
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = option.fps.toString(),
                                color = if (selected) ResolutionPanelAccent else Color.White.copy(alpha = 0.94f),
                                fontSize = 14.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                letterSpacing = 0.2.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) ResolutionPanelAccent.copy(alpha = 0.13f)
                                        else Color.Transparent
                                    )
                                    .clickable { onFpsSelected(option) }
                                    .padding(horizontal = 6.dp, vertical = 5.dp)
                                    .testTag("video_fps_option_${option.fps}")
                            )
                        }
                    }
                }

                Spacer(Modifier.height(11.dp))

                Text(
                    text = when (selectedResolution) {
                        VideoResolutionPreset.UHD_2160P -> "Ultra HD resolution"
                        VideoResolutionPreset.FHD_1080P -> "Full HD resolution"
                        VideoResolutionPreset.HD_720P -> "HD resolution"
                    },
                    color = Color.White.copy(alpha = 0.79f),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SelectorRow(
    label: String,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(46.dp)
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
