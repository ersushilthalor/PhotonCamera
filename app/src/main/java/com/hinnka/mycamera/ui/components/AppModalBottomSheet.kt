package com.hinnka.mycamera.ui.components

import android.view.View
import android.view.ViewParent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.areStatusBarsVisible
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Opaque panel colors shared by sheets and in-window camera panels. */
object AppPanelMaterial {
    val Tint = Color(0xFF1C1C1E)
    val EdgeHighlight = Color(0xFF39393B)
}

/**
 * Sheet state used by [AppModalBottomSheet]. The half-expanded anchor is skipped so the anchor set
 * never changes when content height crosses half of the window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberAppModalBottomSheetState(): SheetState =
    rememberModalBottomSheetState(skipPartiallyExpanded = true)

/**
 * Project-wide [ModalBottomSheet] owning the sheet material and a sheet geometry that does not
 * change while it is dragged.
 *
 * Material: callers may select a fully opaque [containerColor] and [shape] for scenes with a dedicated
 * look; the default panel fill and modal scrim are opaque.
 *
 * Geometry: Material3's sheet drives drags coming from nested scrolling through `dispatchRawDelta`,
 * which does not hold the drag mutex. Whenever the sheet height or the available height changes
 * during such a drag, the anchors are rebuilt and the offset snaps back to an anchor, so the sheet
 * can't be pulled down or twitches every frame. This wrapper removes every source of such changes:
 * - The top inset is not part of [ModalBottomSheet]'s `contentWindowInsets`, because the sheet
 *   consumes the top inset by its current offset and the content height would follow the drag.
 * - The top gap is reserved inside the dialog from insets that ignore visibility, so status bar
 *   show/hide animations can't move the anchors.
 * - The dialog window mirrors the host's status bar visibility. A dialog window takes the insets
 *   control when it gains focus and would otherwise bring back a status bar the host has hidden.
 * - The half-expanded anchor is skipped by the default [sheetState].
 *
 * Content contract: keep headers outside the scrolling container so they drag the sheet directly,
 * and bound the single vertical scrolling container with `Modifier.weight(1f, fill = false)` (or
 * `weight(1f)` for a sheet that always fills the available height). Bottom system bar insets are
 * already applied, so content must not add `navigationBarsPadding()`. Content must not paint an
 * opaque background over the sheet material.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberAppModalBottomSheetState(),
    containerColor: Color? = null,
    shape: Shape? = null,
    contentColor: Color = Color.White,
    dragHandle: @Composable (() -> Unit)? = {
        BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.28f))
    },
    content: @Composable ColumnScope.() -> Unit,
) {
    // Read in the host composition, where the insets belong to the host window.
    val hostStatusBarsVisible = WindowInsets.areStatusBarsVisible
    val resolvedContainerColor = containerColor?.takeIf { it.alpha >= 1f } ?: AppPanelMaterial.Tint
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape ?: SheetShape,
        containerColor = resolvedContainerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        scrimColor = BottomSheetDefaults.ScrimColor,
        dragHandle = dragHandle,
        contentWindowInsets = {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        },
    ) {
        // Everything below runs in the dialog composition, where the insets belong to the dialog.
        val window = LocalView.current.findDialogWindowProvider()?.window
        DisposableEffect(window, hostStatusBarsVisible) {
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (hostStatusBarsVisible) {
                        show(WindowInsetsCompat.Type.statusBars())
                    } else {
                        hide(WindowInsetsCompat.Type.statusBars())
                    }
                }

            }
            onDispose { }
        }
        val topGapPx = WindowInsets.statusBarsIgnoringVisibility
            .union(WindowInsets.displayCutout)
            .getTop(LocalDensity.current)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val maxHeight = if (constraints.hasBoundedHeight) {
                        (constraints.maxHeight - topGapPx).coerceAtLeast(0)
                    } else {
                        constraints.maxHeight
                    }
                    val placeable = measurable.measure(
                        constraints.copy(
                            minHeight = constraints.minHeight.coerceAtMost(maxHeight),
                            maxHeight = maxHeight
                        )
                    )
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
            content = content
        )
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
