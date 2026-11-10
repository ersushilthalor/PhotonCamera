package com.hinnka.mycamera.ui.components

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import java.util.function.Consumer
import kotlin.math.roundToInt

/** Applies the same background-blur material as CameraTopSheet to a compact dialog window. */
@Composable
fun FrostedDialogWindowEffect(
    window: Window?,
    blurRadius: Dp = 28.dp,
    gravity: Int,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
): Boolean {
    val blurEnabled = rememberCrossWindowBlurSupported()
    val blurRadiusPx = with(LocalDensity.current) { blurRadius.toPx().roundToInt() }

    DisposableEffect(window) {
        window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window?.setFrostedBackgroundBlurRadius(0)
        }
    }

    LaunchedEffect(window, blurEnabled, blurRadiusPx, gravity, x, y, width, height) {
        val dialogWindow = window ?: return@LaunchedEffect
        dialogWindow.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialogWindow.attributes = dialogWindow.attributes.apply {
            this.gravity = gravity
            this.x = x
            this.y = y
            this.width = width
            this.height = height
            dimAmount = 0f
        }
        // Background blur is limited to this compact dialog window. Blur-behind would
        // blur the entire camera preview behind the dialog.
        dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        dialogWindow.setFrostedBackgroundBlurRadius(if (blurEnabled) blurRadiusPx else 0)
    }
    return blurEnabled
}

@Composable
private fun rememberCrossWindowBlurSupported(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val windowManager = LocalContext.current.getSystemService(WindowManager::class.java)
        ?: return false
    var enabled by remember(windowManager) { mutableStateOf(windowManager.isCrossWindowBlurEnabled) }
    DisposableEffect(windowManager) {
        val listener = Consumer<Boolean> { enabled = it }
        windowManager.addCrossWindowBlurEnabledListener(listener)
        onDispose { windowManager.removeCrossWindowBlurEnabledListener(listener) }
    }
    return enabled
}

private fun Window.setFrostedBackgroundBlurRadius(radius: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    setBackgroundBlurRadius(radius.coerceAtLeast(0))
}

/** Finds the Dialog window hosting a Compose dialog's view. */
fun View.findFrostedDialogWindow(): Window? {
    var current: ViewParent? = parent
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = (current as? View)?.parent
    }
    return null
}
