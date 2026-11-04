package com.hinnka.mycamera.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Applies the app-wide visual text scale while preserving dp-based layout and touch targets.
 * Multiplies the system font scale so Android accessibility settings remain respected.
 */
@Composable
fun AppUiScale(
    scale: Float = 0.75f,
    content: @Composable () -> Unit
) {
    val systemDensity = LocalDensity.current
    val scaledDensity = Density(
        density = systemDensity.density,
        fontScale = systemDensity.fontScale * scale.coerceIn(0.5f, 1f)
    )
    CompositionLocalProvider(LocalDensity provides scaledDensity, content = content)
}
