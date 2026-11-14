package com.hinnka.mycamera.ui.components

import android.view.ViewConfiguration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.ui.theme.AccentColor
import com.hinnka.mycamera.ui.theme.OnAccentColor
import com.styropyr0.prismal.components.PrismalGlassSlider
import com.styropyr0.prismal.components.PrismalGlassToggle

/**
 * Settings slider row. With a Prismal backdrop this uses the original glass track,
 * spring thumb, refraction and haptics; without one it retains the existing slider.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SliderSettingItem(
    title: String,
    description: String? = null,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    valueTextFormatter: (Float) -> String = { String.format("%.2f", it) },
    resetValue: Float? = null,
    onResetValue: ((Float) -> Unit)? = null,
    toggleValue: Boolean? = null,
    onToggleChange: (Boolean) -> Unit = {},
    enabled: Boolean = toggleValue ?: true,
    modifier: Modifier = Modifier
) {
    val backdrop = LocalSettingsPrismalBackdrop.current
    val shouldShowSlider = enabled || toggleValue == null
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = if (enabled) 1f else 0.45f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal
            )

            if (toggleValue != null) {
                Spacer(modifier = Modifier.width(8.dp))
                if (backdrop != null && enabled) {
                    PrismalGlassToggle(
                        selected = { toggleValue },
                        onSelect = onToggleChange,
                        backdrop = backdrop
                    )
                } else {
                    Switch(
                        checked = toggleValue,
                        onCheckedChange = onToggleChange,
                        modifier = Modifier.size(40.dp, 24.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnAccentColor,
                            checkedTrackColor = AccentColor,
                            uncheckedThumbColor = androidx.compose.ui.graphics.Color.Gray,
                            uncheckedTrackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f),
                            uncheckedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (shouldShowSlider) {
                Text(
                    text = valueTextFormatter(value),
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = if (enabled) 0.8f else 0.35f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        description?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = if (enabled) 0.6f else 0.35f),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }

        if (shouldShowSlider) {
            Spacer(modifier = Modifier.height(8.dp))
            if (backdrop != null && enabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .prismalSliderGestures(
                            touchSlop = touchSlop,
                            doubleTapTimeoutMillis = doubleTapTimeout,
                            onTapReset = if (resetValue != null) {
                                {
                                    if (onResetValue != null) onResetValue(resetValue)
                                    else onValueChange(resetValue)
                                }
                            } else null,
                            onValueChangeFinished = onValueChangeFinished
                        )
                ) {
                    PrismalGlassSlider(
                        value = { value },
                        onValueChange = onValueChange,
                        valueRange = valueRange,
                        visibilityThreshold = ((valueRange.endInclusive - valueRange.start) * 0.005f)
                            .coerceAtLeast(0.001f),
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                CustomSlider(
                    value = value,
                    onValueChange = onValueChange,
                    onValueChangeFinished = onValueChangeFinished,
                    enabled = enabled,
                    valueRange = valueRange,
                    thumbColor = androidx.compose.ui.graphics.Color.White.copy(alpha = if (enabled) 1f else 0.35f),
                    activeTrackColor = AccentColor.copy(alpha = if (enabled) 1f else 0.35f),
                    inactiveTrackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = if (enabled) 0.2f else 0.12f),
                    onDoubleTap = {
                        if (enabled && resetValue != null) {
                            if (onResetValue != null) onResetValue(resetValue)
                            else {
                                onValueChange(resetValue)
                                onValueChangeFinished?.invoke()
                            }
                        }
                    }
                )
            }
        }
    }
}

/**
 * Observes the glass slider's pointer stream without consuming it, preserving the
 * library's drag physics while keeping the settings row's reset and commit callbacks.
 */
private fun Modifier.prismalSliderGestures(
    touchSlop: Float,
    doubleTapTimeoutMillis: Long,
    onTapReset: (() -> Unit)?,
    onValueChangeFinished: (() -> Unit)?
): Modifier {
    if (onTapReset == null && onValueChangeFinished == null) return this

    return pointerInput(touchSlop, doubleTapTimeoutMillis, onTapReset, onValueChangeFinished) {
        var pointerDown = false
        var startPosition = Offset.Zero
        var moved = false
        var lastTapTime = 0L

        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull() ?: continue

                if (change.pressed && !pointerDown) {
                    pointerDown = true
                    startPosition = change.position
                    moved = false
                } else if (change.pressed && pointerDown) {
                    if ((change.position - startPosition).getDistance() > touchSlop) {
                        moved = true
                    }
                } else if (!change.pressed && pointerDown) {
                    if (!moved && onTapReset != null) {
                        val now = event.uptimeMillis
                        if (lastTapTime != 0L && now - lastTapTime <= doubleTapTimeoutMillis) {
                            onTapReset()
                            onValueChangeFinished?.invoke()
                            lastTapTime = 0L
                        } else {
                            lastTapTime = now
                            onValueChangeFinished?.invoke()
                        }
                    } else {
                        onValueChangeFinished?.invoke()
                    }
                    pointerDown = false
                }
            }
        }
    }
}
