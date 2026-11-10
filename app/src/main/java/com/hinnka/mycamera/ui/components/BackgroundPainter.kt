package com.hinnka.mycamera.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import com.hinnka.mycamera.R
import com.hinnka.mycamera.utils.GaussianBlur
import com.hinnka.mycamera.viewmodel.CameraViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** App-wide switch for the Liquid Glass material, provided above the navigation host. */
val LocalLiquidGlassEnabled = compositionLocalOf { false }
val LocalLiquidGlassBackdrop = compositionLocalOf<LiquidGlassBackdrop?> { null }

/** Live settings shared by camera controls, settings cards and bottom sheets. */
data class BackgroundSurfaceSettings(
    val transparency: Float = 0f,
    val blurRadius: Float = 0f,
)

val LocalBackgroundSurfaceSettings = compositionLocalOf { BackgroundSurfaceSettings() }

/**
 * Reusable panel material. Liquid Glass takes precedence; otherwise the wallpaper controls adjust
 * each panel's backdrop blur and opacity without changing camera exposure or preview rendering.
 */
fun Modifier.liquidGlassSurface(
    shape: Shape,
    fallbackColor: Color,
): Modifier = composed {
    val glassEnabled = LocalLiquidGlassEnabled.current
    val backdrop = LocalLiquidGlassBackdrop.current
    val settings = LocalBackgroundSurfaceSettings.current
    val transparency = settings.transparency
        .takeIf { it.isFinite() }
        ?.coerceIn(0f, 100f)
        ?: 0f
    val blurRadius = settings.blurRadius
        .takeIf { it.isFinite() }
        ?.coerceIn(0f, 30f)
        ?: 0f
    val panelAlpha = if (glassEnabled) 1f else 1f - transparency / 100f
    val panelColor = fallbackColor.copy(alpha = fallbackColor.alpha * panelAlpha)
    val canSampleBackdrop = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    if (glassEnabled) {
        if (canSampleBackdrop) {
            this
                .shadow(
                    elevation = 18.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color(0xFFB8D8FF).copy(alpha = 0.20f),
                    spotColor = Color.Black.copy(alpha = 0.34f),
                )
                .clip(shape)
                .liquidGlassBackdropEffect(
                    backdrop = backdrop!!,
                    style = LiquidGlassStyle(
                        blurRadius = 12.dp,
                        refractionHeight = 24.dp,
                        refractionAmount = 48.dp,
                        saturation = 1.5f,
                        tint = Color(0x0DFFFFFF),
                        highlightWidth = 0.5.dp,
                        highlightBlur = 0.25.dp,
                        highlightAlpha = 0.5f,
                        fallbackColor = fallbackColor,
                    ),
                    cornerRadius = 18.dp,
                )
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color(0xFFD8E9FF).copy(alpha = 0.06f),
                            Color.Transparent,
                        )
                    ),
                    shape = shape,
                )
        } else {
            // Android 11 keeps the gradient material and never invokes unsupported RenderEffects.
            this
                .shadow(
                    elevation = 18.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color(0xFFB8D8FF).copy(alpha = 0.20f),
                    spotColor = Color.Black.copy(alpha = 0.34f),
                )
                .clip(shape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.30f),
                            Color(0xFFD8E9FF).copy(alpha = 0.17f),
                            Color(0xFF91B9E9).copy(alpha = 0.10f),
                            Color.White.copy(alpha = 0.07f),
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.68f),
                            Color(0xFFD9E9FF).copy(alpha = 0.24f),
                            Color.White.copy(alpha = 0.10f),
                            Color.White.copy(alpha = 0.42f),
                        )
                    ),
                    shape = shape,
                )
        }
    } else {
        var surface = this
        if (canSampleBackdrop && blurRadius > 0f) {
            surface = surface
                .clip(shape)
                .liquidGlassBackdropEffect(
                    backdrop = backdrop!!,
                    style = LiquidGlassStyle(
                        blurRadius = blurRadius.dp,
                        refractionHeight = 1.dp,
                        refractionAmount = 0.dp,
                        saturation = 1f,
                        tint = Color.Transparent,
                        highlightWidth = 0.dp,
                        highlightAlpha = 0f,
                        fallbackColor = panelColor,
                    ),
                    cornerRadius = 18.dp,
                )
        } else if (panelColor.alpha > 0f) {
            surface = surface.clip(shape)
        }
        if (panelColor.alpha > 0f) {
            surface = surface.background(panelColor)
        }
        surface
    }
}

@Composable
fun rememberBackgroundPainter(
    viewModel: CameraViewModel,
    legacyBlurRadius: Float = 0f,
): Painter {
    val bgImage by viewModel.backgroundImage.collectAsState()
    val context = LocalContext.current
    val regularPainter = remember(bgImage) {
        if (bgImage.startsWith("/")) {
            try {
                val bitmap = BitmapFactory.decodeFile(bgImage)
                bitmap?.let { BitmapPainter(it.asImageBitmap()) }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    } ?: run {
        if (!bgImage.startsWith("/")) {
            val resId = context.resources.getIdentifier(bgImage, "drawable", context.packageName)
            if (resId != 0) painterResource(resId) else painterResource(R.drawable.camera_bg)
        } else {
            painterResource(R.drawable.camera_bg)
        }
    }

    // Android 11 has no Compose RenderEffect blur. Blur a small cached bitmap off the main thread;
    // changing the slider reuses prior values and never blurs on every recomposition.
    val blurBucket = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        legacyBlurRadius.takeIf { it.isFinite() }?.coerceIn(0f, 30f)?.roundToInt() ?: 0
    } else {
        0
    }
    val blurredBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = bgImage,
        key2 = blurBucket,
    ) {
        value = if (blurBucket > 0) {
            withContext(Dispatchers.Default) {
                LegacyBackgroundBlurCache.get(context.applicationContext, bgImage, blurBucket)
            }
        } else {
            null
        }
    }
    val blurredPainter = remember(blurredBitmap) {
        blurredBitmap?.let { BitmapPainter(it.asImageBitmap()) }
    }
    return blurredPainter ?: regularPainter
}

private object LegacyBackgroundBlurCache {
    private const val MAX_BITMAP_EDGE = 480
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun get(context: Context, bgImage: String, blurRadiusDp: Int): Bitmap? {
        val density = context.resources.displayMetrics.density
        val key = "$bgImage|$blurRadiusDp|$density"
        synchronized(cache) {
            cache.get(key)?.let { return it }
        }

        val bitmap = decodeWallpaper(context, bgImage) ?: return null
        val displayLongEdge = max(
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels,
        ).coerceAtLeast(MAX_BITMAP_EDGE).toFloat()
        val bitmapToScreenScale = displayLongEdge / max(bitmap.width, bitmap.height).toFloat()
        val sigma = (blurRadiusDp * density * 0.5f / bitmapToScreenScale).coerceAtLeast(0.5f)
        try {
            GaussianBlur.blur(bitmap, sigma, sigma)
        } catch (_: Exception) {
            bitmap.recycle()
            return null
        }

        synchronized(cache) {
            cache.put(key, bitmap)
            return cache.get(key) ?: bitmap
        }
    }

    private fun decodeWallpaper(context: Context, bgImage: String): Bitmap? {
        if (bgImage.startsWith("/")) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(bgImage, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                val sampleSize = ceil(
                    max(bounds.outWidth, bounds.outHeight).toFloat() / MAX_BITMAP_EDGE
                ).toInt().coerceAtLeast(1)
                val decoded = BitmapFactory.decodeFile(
                    bgImage,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inMutable = true
                    },
                )
                if (decoded != null) return decoded
            }
        }

        val requestedId = if (bgImage.startsWith("/")) {
            0
        } else {
            context.resources.getIdentifier(bgImage, "drawable", context.packageName)
        }
        val resourceId = requestedId.takeIf { it != 0 } ?: R.drawable.camera_bg
        val drawable = ContextCompat.getDrawable(context, resourceId) ?: return null
        val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: MAX_BITMAP_EDGE
        val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: MAX_BITMAP_EDGE
        val scale = minOf(1f, MAX_BITMAP_EDGE.toFloat() / max(sourceWidth, sourceHeight))
        val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
        }
    }
}

@Composable
fun AppBackground(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    transparency: Float? = null,
    blurRadius: Float? = null,
) {
    val backdrop = LocalLiquidGlassBackdrop.current
    val userPreferences by viewModel.userPreferences.collectAsState()
    val liquidGlassEnabled = userPreferences.liquidGlassEnabled
    val effectiveBlur = if (liquidGlassEnabled) {
        0f
    } else {
        (blurRadius ?: userPreferences.backgroundBlur)
            .takeIf { it.isFinite() }
            ?.coerceIn(0f, 30f)
            ?: 0f
    }
    val painter = rememberBackgroundPainter(
        viewModel = viewModel,
        legacyBlurRadius = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) effectiveBlur else 0f,
    )

    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // Transparency is a panel property; keep the wallpaper visible beneath transparent panels.
        modifier = modifier
            .then(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && effectiveBlur > 0f) {
                    Modifier.blur(effectiveBlur.dp)
                } else {
                    Modifier
                }
            )
            .then(backdrop?.let { Modifier.liquidGlassBackdropSource(it) } ?: Modifier),
    )
}
