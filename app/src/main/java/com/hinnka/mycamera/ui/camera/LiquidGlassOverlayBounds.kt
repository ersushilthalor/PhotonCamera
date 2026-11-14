package com.hinnka.mycamera.ui.camera

import androidx.compose.ui.geometry.Rect

/**
 * Converts Compose root-coordinate bounds into the normalized bottom-left coordinate
 * system used by LutRenderer's full-screen OpenGL quad. The panel is clipped to the
 * live preview viewport because the camera renderer must not draw outside its surface.
 */
internal fun createLiquidGlassOverlayBounds(
    panelBounds: Rect?,
    previewBounds: Rect?,
): FloatArray? {
    val panel = panelBounds ?: return null
    val preview = previewBounds ?: return null
    val values = floatArrayOf(
        panel.left, panel.top, panel.right, panel.bottom,
        preview.left, preview.top, preview.right, preview.bottom,
    )
    if (values.any { !it.isFinite() }) return null

    val previewWidth = preview.right - preview.left
    val previewHeight = preview.bottom - preview.top
    if (previewWidth <= 0f || previewHeight <= 0f) return null

    val left = maxOf(panel.left, preview.left)
    val top = maxOf(panel.top, preview.top)
    val right = minOf(panel.right, preview.right)
    val bottom = minOf(panel.bottom, preview.bottom)
    if (right <= left || bottom <= top) return null

    val leftNormalized = ((left - preview.left) / previewWidth).coerceIn(0f, 1f)
    val rightNormalized = ((right - preview.left) / previewWidth).coerceIn(0f, 1f)

    // Compose uses top-left origin; the GL quad's texture coordinates use bottom-left.
    val bottomNormalized = (1f - (bottom - preview.top) / previewHeight).coerceIn(0f, 1f)
    val topNormalized = (1f - (top - preview.top) / previewHeight).coerceIn(0f, 1f)

    // Keep the Prismal camera panel neutral: its existing Compose implementation does
    // not apply the legacy blue tintAlpha field; surfaceTintAlpha remains configurable.
    return floatArrayOf(leftNormalized, bottomNormalized, rightNormalized, topNormalized, 0f)
}
