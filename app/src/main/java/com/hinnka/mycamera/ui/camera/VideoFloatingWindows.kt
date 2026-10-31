package com.hinnka.mycamera.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.lut.LutInfo
import com.hinnka.mycamera.video.VideoFloatingWindowAppearance
import com.hinnka.mycamera.video.VideoFloatingWindowGlassStyle
import com.hinnka.mycamera.video.VideoLogLutMode
import com.hinnka.mycamera.video.VideoLogProfile

private val VideoFloatingAccent = Color(0xFFFFD45A)
private val VideoFloatingText = Color(0xFFF7F7F7)
private val VideoFloatingMuted = Color(0xFFB8BDC8)

@Composable
internal fun VideoLogFloatingWindow(
    availableProfiles: List<VideoLogProfile>,
    selectedProfile: VideoLogProfile,
    logLutMode: VideoLogLutMode,
    enabled: Boolean,
    appearance: VideoFloatingWindowAppearance,
    onSelectProfile: (VideoLogProfile) -> Unit,
    onLogLutModeSelected: (VideoLogLutMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var optionsExpanded by remember { mutableStateOf(false) }
    val profiles = remember(availableProfiles) {
        val distinct = availableProfiles.distinct()
        if (distinct.any { it.isEnabled }) distinct else VideoLogProfile.entries
    }

    VideoFloatingWindowCard(
        modifier = modifier.testTag("video_log_floating_window"),
        appearance = appearance
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingWindowAction(
                active = optionsExpanded,
                onClick = { optionsExpanded = !optionsExpanded },
                contentDescription = stringResource(R.string.video_floating_window_options)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = if (optionsExpanded) VideoFloatingAccent else VideoFloatingText,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                profiles.forEach { profile ->
                    FloatingWindowChoice(
                        label = profile.displayName,
                        selected = profile == selectedProfile,
                        enabled = enabled,
                        onClick = { onSelectProfile(profile) }
                    )
                }
            }
            Spacer(Modifier.width(7.dp))
            FloatingWindowAction(
                active = false,
                onClick = onDismiss,
                contentDescription = stringResource(R.string.video_floating_window_close)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = VideoFloatingText,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = optionsExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                Text(
                    text = stringResource(R.string.video_log_lut_output),
                    color = VideoFloatingMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                VideoLogLutModeSelector(
                    mode = logLutMode,
                    enabled = enabled,
                    onModeSelected = onLogLutModeSelected
                )
            }
        }
    }
}

@Composable
internal fun VideoLutFloatingWindow(
    availableLuts: List<LutInfo>,
    selectedLutId: String,
    logProfile: VideoLogProfile,
    logLutMode: VideoLogLutMode,
    enabled: Boolean,
    appearance: VideoFloatingWindowAppearance,
    onSelectLut: (String?) -> Unit,
    onLogLutModeSelected: (VideoLogLutMode) -> Unit,
    onManageLuts: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var optionsExpanded by remember { mutableStateOf(false) }
    val compatibleLuts = remember(availableLuts, logProfile) {
        availableLuts.filter { info ->
            !logProfile.isEnabled || logProfile.matchesLut(info.inputCurve, info.inputColorSpace)
        }
    }

    VideoFloatingWindowCard(
        modifier = modifier.testTag("video_lut_floating_window"),
        appearance = appearance
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingWindowAction(
                active = optionsExpanded,
                onClick = { optionsExpanded = !optionsExpanded },
                contentDescription = stringResource(R.string.video_floating_window_options)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = if (optionsExpanded) VideoFloatingAccent else VideoFloatingText,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FloatingWindowChoice(
                    label = stringResource(R.string.video_lut_off),
                    selected = selectedLutId == "none",
                    enabled = enabled,
                    onClick = { onSelectLut(null) }
                )
                compatibleLuts.forEach { info ->
                    FloatingWindowChoice(
                        label = info.getName(),
                        selected = info.id == selectedLutId,
                        enabled = enabled,
                        onClick = { onSelectLut(info.id) }
                    )
                }
            }
            Spacer(Modifier.width(7.dp))
            FloatingWindowAction(
                active = false,
                onClick = onDismiss,
                contentDescription = stringResource(R.string.video_floating_window_close)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = VideoFloatingText,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = optionsExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)
            ) {
                VideoLogLutModeSelector(
                    mode = logLutMode,
                    enabled = enabled,
                    onModeSelected = onLogLutModeSelected
                )
                TextButton(
                    onClick = onManageLuts,
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = stringResource(R.string.video_floating_window_manage_luts),
                        color = VideoFloatingAccent
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoFloatingWindowCard(
    appearance: VideoFloatingWindowAppearance,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    content: @Composable () -> Unit
) {
    val normalized = remember(appearance) { appearance.normalized() }
    val transparency = normalized.transparency
    val baseOpacity = (0.90f - transparency * 0.58f).coerceIn(0.18f, 0.9f)
    val frostAlpha = (normalized.blurStrength / 50f * 0.30f).coerceIn(0f, 0.30f)
    val tint = when (normalized.glassStyle) {
        VideoFloatingWindowGlassStyle.LIQUID_GLASS -> Color(0xFF111722)
        VideoFloatingWindowGlassStyle.FROSTED_GLASS -> Color(0xFF303847)
        VideoFloatingWindowGlassStyle.TRANSPARENT_GLASS -> Color(0xFF10141B)
        VideoFloatingWindowGlassStyle.SOLID_DARK -> Color(0xFF080B11)
    }
    val gradient = when (normalized.glassStyle) {
        VideoFloatingWindowGlassStyle.LIQUID_GLASS -> Brush.linearGradient(
            listOf(Color(0xFF3C4659).copy(alpha = baseOpacity), tint.copy(alpha = baseOpacity), Color(0xFF0A0D14).copy(alpha = baseOpacity))
        )
        VideoFloatingWindowGlassStyle.FROSTED_GLASS -> Brush.verticalGradient(
            listOf(Color(0xFF667080).copy(alpha = baseOpacity * 0.7f), tint.copy(alpha = baseOpacity))
        )
        VideoFloatingWindowGlassStyle.TRANSPARENT_GLASS -> Brush.verticalGradient(
            listOf(Color(0xFF697589).copy(alpha = baseOpacity * 0.42f), tint.copy(alpha = baseOpacity * 0.8f))
        )
        VideoFloatingWindowGlassStyle.SOLID_DARK -> Brush.verticalGradient(
            listOf(Color(0xFF202632).copy(alpha = baseOpacity), tint.copy(alpha = baseOpacity))
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 380.dp)
            .graphicsScale(normalized.windowScale)
            .shadow(18.dp, shape, clip = false)
            .clip(shape)
            .background(gradient)
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            VideoFloatingAccent.copy(alpha = 0.38f),
                            Color.White.copy(alpha = 0.16f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    )
                ),
                shape
            )
    ) {
        Box(
            Modifier.fillMaxWidth().height(2.dp).background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.56f), VideoFloatingAccent.copy(alpha = 0.2f), Color.Transparent)
                )
            )
        )
        Box(Modifier.fillMaxWidth().background(Color.White.copy(alpha = frostAlpha))) {
            content()
        }
    }
}

private fun Modifier.graphicsScale(scale: Float): Modifier = androidx.compose.ui.graphics.graphicsLayer {
    scaleX = scale
    scaleY = scale
}

@Composable
private fun FloatingWindowAction(
    active: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (active) VideoFloatingAccent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .testTag("video_floating_window_action"),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun FloatingWindowChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = when {
            !enabled -> VideoFloatingMuted.copy(alpha = 0.45f)
            selected -> VideoFloatingAccent
            else -> VideoFloatingText
        },
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 7.dp)
    )
}
