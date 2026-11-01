package com.hinnka.mycamera.processor

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Oppo-style photo rendering controls applied to the final rendered photo.
 * The input remains immutable; an independent output bitmap is returned.
 */
object OppoPhotoPipelineEngine {
    const val METADATA_KEY = "oppo.customPipeline.v1"

    suspend fun process(source: Bitmap, settingsJson: String?): Bitmap =
        withContext(Dispatchers.Default) {
            if (settingsJson.isNullOrBlank()) return@withContext source
            val params = runCatching { JSONObject(settingsJson) }.getOrNull()
                ?: return@withContext source
            val profile = params.optString("profile", "NATURAL")
            val profileMatrix = when (profile) {
                "HASSELBLAD" -> floatArrayOf(1.01f, -0.01f, 0f, -0.01f, 1.01f, 0f, 0f, -0.01f, 1.01f)
                "SAMSUNG" -> floatArrayOf(1.05f, -0.03f, -0.02f, -0.02f, 1.04f, -0.02f, -0.02f, -0.02f, 1.06f)
                "PIXEL" -> floatArrayOf(1.02f, -0.01f, -0.01f, -0.01f, 1.02f, -0.01f, -0.01f, -0.01f, 1.03f)
                "IPHONE" -> floatArrayOf(1.04f, 0f, -0.03f, -0.01f, 1.02f, -0.01f, -0.01f, 0f, 0.99f)
                else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }
            val exposure = 2.0.pow(params.optDouble("exposure", 0.0).coerceIn(-2.0, 2.0)).toFloat()
            val contrast = params.optDouble("contrast", 1.0).toFloat().coerceIn(0.6f, 1.4f)
            val highlights = params.optDouble("highlights", -15.0).toFloat().coerceIn(-100f, 100f) / 100f
            val shadows = params.optDouble("shadows", 12.0).toFloat().coerceIn(-100f, 100f) / 100f
            val saturation = 1f + params.optDouble("saturation", 0.0).toFloat().coerceIn(-100f, 100f) / 100f
            val vibrance = params.optDouble("vibrance", 5.0).toFloat().coerceIn(-100f, 100f) / 100f
            val temperature = params.optDouble("temperature", 0.0).toFloat().coerceIn(-100f, 100f) / 100f
            val tint = params.optDouble("tint", 0.0).toFloat().coerceIn(-100f, 100f) / 100f
            val sharpness = params.optDouble("sharpness", 18.0).toFloat().coerceIn(0f, 100f) / 100f
            val texture = params.optDouble("texture", 0.0).toFloat().coerceIn(-100f, 100f) / 100f
            val noiseReduction = params.optDouble("noiseReduction", 10.0).toFloat().coerceIn(0f, 100f) / 100f
            val detailPreservation = params.optDouble("detailPreservation", 88.0).toFloat().coerceIn(0f, 100f) / 100f

            val width = source.width
            val height = source.height
            if (width <= 0 || height <= 0) return@withContext source
            // Work in bounded 32-row chunks; this keeps temporary pixel memory small
            // while preserving the four-neighbour detail filter across chunk boundaries.
            val output = IntArray(width * height)
            val chunkRows = 32
            var chunkStart = 0
            while (chunkStart < height) {
                val chunkEnd = min(height, chunkStart + chunkRows)
                val sourceStartRow = max(0, chunkStart - 1)
                val sourceEndRow = min(height, chunkEnd + 1)
                val sourceRows = sourceEndRow - sourceStartRow
                val chunkPixels = IntArray(width * sourceRows)
                source.getPixels(chunkPixels, 0, width, 0, sourceStartRow, width, sourceRows)

                fun pixelAt(x: Int, y: Int): Int =
                    chunkPixels[(y - sourceStartRow) * width + x]
                fun lum(color: Int): Float = (
                    0.2126f * ((color shr 16) and 0xFF) +
                        0.7152f * ((color shr 8) and 0xFF) +
                        0.0722f * (color and 0xFF)
                    ) / 255f

                for (y in chunkStart until chunkEnd) {
                    val row = y * width
                    for (x in 0 until width) {
                        val index = row + x
                        val color = pixelAt(x, y)
                        var r = ((color shr 16) and 0xFF) / 255f
                        var g = ((color shr 8) and 0xFF) / 255f
                        var b = (color and 0xFF) / 255f

                        r *= exposure * (1f + temperature * 0.25f + tint * 0.12f)
                        g *= exposure * (1f - tint * 0.25f)
                        b *= exposure * (1f - temperature * 0.25f + tint * 0.12f)

                        val mr = profileMatrix[0] * r + profileMatrix[1] * g + profileMatrix[2] * b
                        val mg = profileMatrix[3] * r + profileMatrix[4] * g + profileMatrix[5] * b
                        val mb = profileMatrix[6] * r + profileMatrix[7] * g + profileMatrix[8] * b
                        r = mr; g = mg; b = mb

                        var luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                        val shadowWeight = (1f - luma) * (1f - luma)
                        val highlightWeight = luma * luma
                        luma += shadows * 0.38f * shadowWeight - highlights * 0.38f * highlightWeight
                        luma = ((luma - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
                        val rollOff = max(0f, luma - 0.78f)
                        luma = (luma - rollOff * rollOff * 0.45f).coerceIn(0f, 1f)

                        val oldLuma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceAtLeast(0.0001f)
                        val lumaScale = luma / oldLuma
                        r *= lumaScale; g *= lumaScale; b *= lumaScale

                        val grey = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        val chroma = max(r, max(g, b)) - min(r, min(g, b))
                        val sat = saturation + vibrance * (1f - chroma).coerceIn(0f, 1f)
                        r = grey + (r - grey) * sat
                        g = grey + (g - grey) * sat
                        b = grey + (b - grey) * sat

                        if (x > 0 && x < width - 1 && y > 0 && y < height - 1) {
                            val neighbor = (
                                lum(pixelAt(x - 1, y)) +
                                    lum(pixelAt(x + 1, y)) +
                                    lum(pixelAt(x, y - 1)) +
                                    lum(pixelAt(x, y + 1))
                                ) * 0.25f
                            val center = lum(color)
                            val detail = center - neighbor
                            val strength = sharpness * 0.55f + texture * 0.22f
                            val denoiseBlend = noiseReduction * (1f - detailPreservation) * 0.18f
                            val adjusted = (luma + detail * strength) * (1f - denoiseBlend) + neighbor * denoiseBlend
                            val ratio = adjusted.coerceIn(0f, 1f) / luma.coerceAtLeast(0.0001f)
                            r *= ratio; g *= ratio; b *= ratio
                        }

                        val ri = (r.coerceIn(0f, 1f) * 255f).roundToInt()
                        val gi = (g.coerceIn(0f, 1f) * 255f).roundToInt()
                        val bi = (b.coerceIn(0f, 1f) * 255f).roundToInt()
                        output[index] = (color and -0x1000000) or (ri shl 16) or (gi shl 8) or bi
                    }
                }
                chunkStart = chunkEnd
            }

            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(output, 0, width, 0, 0, width, height)
            }
        }
}
