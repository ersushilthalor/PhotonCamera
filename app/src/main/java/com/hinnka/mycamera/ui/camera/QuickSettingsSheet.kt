package com.hinnka.mycamera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.ui.theme.AccentColor

internal data class QuickSettingAction(
    val title: String,
    val value: String,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
internal fun QuickSettingsSheet(
    title: String,
    generalTabLabel: String,
    proTabLabel: String,
    moreSettingsLabel: String,
    closeDescription: String,
    generalActions: List<QuickSettingAction>,
    proActions: List<QuickSettingAction>,
    onMoreSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    var showProOptions by rememberSaveable { mutableStateOf(false) }
    val panelShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val interactionSource = remember { MutableInteractionSource() }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onDismiss
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .clip(panelShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xF02B2D32), Color(0xF0121418))
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.14f), panelShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 14.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.34f))
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onMoreSettings) {
                    Text(
                        text = moreSettingsLabel,
                        color = AccentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = closeDescription,
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                QuickSettingsTab(
                    text = generalTabLabel,
                    selected = !showProOptions,
                    modifier = Modifier.weight(1f),
                    onClick = { showProOptions = false }
                )
                QuickSettingsTab(
                    text = proTabLabel,
                    selected = showProOptions,
                    modifier = Modifier.weight(1f),
                    onClick = { showProOptions = true }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            val selectedActions = if (showProOptions) proActions else generalActions
            selectedActions.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    pair.forEach { action ->
                        QuickSettingTile(
                            action = action,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun QuickSettingsTab(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) AccentColor else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color.Black else Color.White.copy(alpha = 0.82f),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun QuickSettingTile(
    action: QuickSettingAction,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(
                if (action.active) AccentColor.copy(alpha = 0.17f)
                else Color.White.copy(alpha = 0.065f)
            )
            .border(
                1.dp,
                if (action.active) AccentColor.copy(alpha = 0.56f)
                else Color.White.copy(alpha = 0.12f),
                shape
            )
            .clickable(enabled = action.enabled, onClick = action.onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = action.title,
                modifier = Modifier.weight(1f),
                color = Color.White.copy(alpha = if (action.enabled) 0.76f else 0.38f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            if (action.active) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AccentColor,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Text(
            text = action.value,
            color = Color.White.copy(alpha = if (action.enabled) 1f else 0.38f),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}
