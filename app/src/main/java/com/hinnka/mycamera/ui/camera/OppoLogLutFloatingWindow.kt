package com.hinnka.mycamera.ui.camera

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.lut.LutInfo
import com.hinnka.mycamera.ui.icons.AppIcons
import com.hinnka.mycamera.ui.theme.AccentColor

@Composable
internal fun OppoLogLutFloatingWindow(
    isOpen: Boolean,
    availableLuts: List<LutInfo>,
    selectedLutId: String,
    intensity: Float,
    backdrop: Bitmap?,
    onLutSelected: (String) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showIntensity by remember { mutableStateOf(false) }
    val backdropImage = remember(backdrop) { backdrop?.takeUnless { it.isRecycled }?.asImageBitmap() }
    val shape = RoundedCornerShape(22.dp)
    AnimatedVisibility(
        visible = isOpen,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.94f, animationSpec = spring(dampingRatio = 0.78f)),
        exit = fadeOut() + scaleOut(targetScale = 0.96f, animationSpec = spring(dampingRatio = 0.86f))
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(0.9f).widthIn(min = 260.dp, max = 332.dp)
                .shadow(20.dp, shape).clip(shape)
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
        ) {
            if (backdropImage != null) {
                Image(
                    bitmap = backdropImage, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().blur(24.dp)
                )
            }
            Box(Modifier.matchParentSize().background(
                Brush.horizontalGradient(listOf(Color(0xC914161E), Color(0xE614161E), Color(0xC914161E)))
            ))
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 5.dp, top = 5.dp, end = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(onClick = { showIntensity = !showIntensity }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            AppIcons.Tune, contentDescription = stringResource(R.string.filter_intensity),
                            tint = if (showIntensity) AccentColor else Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.video_option_off),
                            color = if (selectedLutId == "none") AccentColor else Color.White.copy(alpha = 0.78f),
                            fontSize = 13.sp,
                            fontWeight = if (selectedLutId == "none") FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.clip(CircleShape).clickable { onLutSelected("none") }
                                .padding(horizontal = 4.dp, vertical = 7.dp)
                        )
                        availableLuts.forEach { lut ->
                            val selected = lut.id == selectedLutId
                            Text(
                                lut.getName(),
                                color = if (selected) AccentColor else Color.White.copy(alpha = 0.82f),
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { onLutSelected(lut.id) }
                                    .padding(horizontal = 5.dp, vertical = 7.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = onDismissRequest, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Close, contentDescription = stringResource(R.string.close),
                            tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(17.dp)
                        )
                    }
                }
                AnimatedVisibility(visible = showIntensity) {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 9.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.filter_intensity), color = Color.White.copy(alpha = 0.86f), fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${(intensity.coerceIn(0f, 2f) * 100f).toInt()}%",
                                color = AccentColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        Slider(value = intensity.coerceIn(0f, 2f), onValueChange = onIntensityChange, valueRange = 0f..2f)
                    }
                }
            }
        }
    }
}
