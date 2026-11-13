package com.hinnka.mycamera.ui.components

import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.roundToInt

/**
 * Native Compose glass styling inspired by the supplied GlassiFy web component:
 * a translucent body, soft specular sheen and a fine multi-stop edge highlight.
 *
 * This is intentionally a UI-surface treatment, not a camera-preview capture effect.
 * The camera preview is rendered by GLSurfaceView and must not be read back or blurred
 * just to decorate controls.
 */
fun Modifier.glassUiSurface(
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color = Color(0xD91B1D24),
    rimStrength: Float = 1f,
): Modifier {
    val rim = rimStrength.coerceIn(0f, 1.5f)
    return this
        .clip(shape)
        .background(tint)
        .background(
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.13f * rim),
                    Color.White.copy(alpha = 0.035f * rim),
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.12f * rim),
                )
            )
        )
        .border(
            width = 0.85.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.48f * rim),
                    Color.White.copy(alpha = 0.09f * rim),
                    Color.White.copy(alpha = 0.13f * rim),
                    Color.White.copy(alpha = 0.34f * rim),
                )
            ),
            shape = shape,
        )
}

/** Small glass tile with a distinct but restrained selected state. */
fun Modifier.glassUiTile(
    shape: Shape = RoundedCornerShape(12.dp),
    selected: Boolean = false,
    selectedTint: Color = Color(0xCCFFFFFF),
): Modifier = glassUiSurface(
    shape = shape,
    tint = if (selected) selectedTint else Color(0x251F2128),
    rimStrength = if (selected) 1.15f else 0.8f,
)

/**
 * Add real Android cross-window backdrop blur to a Compose dialog. Android 11 and
 * earlier naturally fall back to the translucent dialog material.
 */
@Composable
fun GlassDialogBackdropEffect(radius: Dp = 30.dp) {
    val view = LocalView.current
    val radiusPx = with(LocalDensity.current) { radius.toPx().roundToInt() }
    val window = view.findDialogWindowProvider()

    DisposableEffect(window, radiusPx) {
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = radiusPx }
        }
        onDispose {
            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.attributes = window.attributes.apply { blurBehindRadius = 0 }
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
        }
    }
}

private fun View.findDialogWindowProvider(): DialogWindowProvider? {
    if (this is DialogWindowProvider) return this
    var parent: ViewParent? = parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent
        parent = parent.parent
    }
    return null
}

/**
 * App-wide Material3 AlertDialog with glass tint and system backdrop blur.
 * Keeps the same content/action contract as Material3 AlertDialog.
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(26.dp),
    containerColor: Color = Color(0xCC1B1D22),
    iconContentColor: Color = Color.White,
    titleContentColor: Color = Color.White,
    textContentColor: Color = Color.White.copy(alpha = 0.88f),
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            GlassDialogBackdropEffect()
            confirmButton()
        },
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
        icon = icon,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}
