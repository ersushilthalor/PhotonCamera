package com.hinnka.mycamera.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.video.VideoFloatingWindowAppearance
import com.hinnka.mycamera.video.VideoFloatingWindowGlassStyle
import com.hinnka.mycamera.video.VideoUiPreferences

@Composable
internal fun VideoFloatingWindowAppearanceSettings() {
    val context = LocalContext.current
    var appearance by remember(context) {
        mutableStateOf(VideoUiPreferences.getFloatingWindowAppearance(context))
    }

    fun update(next: VideoFloatingWindowAppearance) {
        appearance = next.normalized()
        VideoUiPreferences.saveFloatingWindowAppearance(context, appearance)
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.video_floating_window_appearance),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.video_floating_window_appearance_description),
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Text(
            text = stringResource(R.string.video_floating_window_glass_style),
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VideoFloatingWindowGlassStyle.entries.forEach { style ->
                val selected = appearance.glassStyle == style
                Surface(
                    modifier = Modifier.weight(1f).clickable {
                        update(appearance.copy(glassStyle = style))
                    },
                    shape = RoundedCornerShape(9.dp),
                    color = if (selected) Color(0xFFFFD45A).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (selected) Color(0xFFFFD45A).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f)
                    )
                ) {
                    Text(
                        text = stringResource(
                            when (style) {
                                VideoFloatingWindowGlassStyle.LIQUID_GLASS -> R.string.video_floating_window_style_liquid
                                VideoFloatingWindowGlassStyle.FROSTED_GLASS -> R.string.video_floating_window_style_frosted
                                VideoFloatingWindowGlassStyle.TRANSPARENT_GLASS -> R.string.video_floating_window_style_transparent
                                VideoFloatingWindowGlassStyle.SOLID_DARK -> R.string.video_floating_window_style_solid
                            }
                        ),
                        color = if (selected) Color(0xFFFFD45A) else Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 9.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        FloatingWindowSlider(
            title = stringResource(R.string.video_floating_window_transparency),
            value = appearance.transparency * 100f,
            valueText = "${(appearance.transparency * 100f).toInt()}%",
            valueRange = 0f..100f,
            onValueChange = { update(appearance.copy(transparency = it / 100f)) }
        )
        FloatingWindowSlider(
            title = stringResource(R.string.video_floating_window_blur),
            value = appearance.blurStrength,
            valueText = "${appearance.blurStrength.toInt()} dp",
            valueRange = 0f..50f,
            onValueChange = { update(appearance.copy(blurStrength = it)) }
        )
        FloatingWindowSlider(
            title = stringResource(R.string.video_floating_window_size),
            value = appearance.windowScale * 100f,
            valueText = "${(appearance.windowScale * 100f).toInt()}%",
            valueRange = 75f..125f,
            onValueChange = { update(appearance.copy(windowScale = it / 100f)) }
        )
    }
}

@Composable
private fun FloatingWindowSlider(
    title: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = Color.White.copy(alpha = 0.88f), fontSize = 13.sp)
        Text(valueText, color = Color(0xFFFFD45A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = Modifier.fillMaxWidth().height(34.dp)
    )
}
