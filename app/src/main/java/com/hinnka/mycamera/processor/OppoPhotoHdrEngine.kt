package com.hinnka.mycamera.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CaptureResult
import android.media.Image
import com.hinnka.mycamera.model.SafeImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class OppoHdrFrameRole {
    REFERENCE_BASE,
    SHORT_HIGHLIGHT,
    LONG_SHADOW,
}

data class OppoHdrInputFrame(
    val image: SafeImage,
    val captureResult: CaptureResult?,
    val role: OppoHdrFrameRole,
    val evOffset: Float,
)

private data class OppoHdrAlignment(
    val shiftX: Float,
    val shiftY: Float,
    val confidence: Float,
    val isAligned: Boolean,
    val mesh: OppoHdrLocalMesh,
) {
    fun displacement(x: Float, y: Float): Pair<Float, Float> {
        val local = mesh.sample(x, y)
        return (shiftX + local.first) to (shiftY + local.second)
    }
}

private class OppoHdrLocalMesh(
    private val cols: Int,
    private val rows: Int,
    private val dx: FloatArray,
    private val dy: FloatArray,
) {
    fun sample(normX: Float, normY: Float): Pair<Float, Float> {
        val px = (normX * (cols - 1)).coerceIn(0f, (cols - 1).toFloat())
        val py = (normY * (rows - 1)).coerceIn(0f, (rows - 1).toFloat())
        val x0 = px.toInt()
        val y0 = py.toInt()
        val x1 = (x0 + 1).coerceAtMost(cols - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val fx = px - x0
        val fy = py - y0
        fun interpolate(values: FloatArray): Float {
            val top = values[y0 * cols + x0] * (1f - fx) + values[y0 * cols + x1] * fx
            val bottom = values[y1 * cols + x0] * (1f - fx) + values[y1 * cols + x1] * fx
            return top * (1f - fy) + bottom * fy
        }
        return interpolate(dx) to interpolate(dy)
    }
}

private data class OppoHdrMotionMask(
    val width: Int,
    val height: Int,
    val values: FloatArray,
) {
    fun sample(normX: Float, normY: Float): Float {
        val px = (normX * (width - 1)).coerceIn(0f, (width - 1).toFloat())
        val py = (normY * (height - 1)).coerceIn(0f, (height - 1).toFloat())
        val x0 = px.toInt()
        val y0 = py.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = px - x0
        val fy = py - y0
        val top = values[y0 * width + x0] * (1f - fx) + values[y0 * width + x1] * fx
        val bottom = values[y1 * width + x0] * (1f - fx) + values[y1 * width + x1] * fx
        return top * (1f - fy) + bottom * fy
    }
}

private data class OppoHdrAlignedFrame(
    val bitmap: Bitmap,
    val alignment: OppoHdrAlignment,
    val motionMask: OppoHdrMotionMask,
    val exposureRatio: Float,
    val evOffset: Float,
    val role: OppoHdrFrameRole,
)

/**
 * OppoCam's ISP-frame HDR path, adapted to PhotonCamera's Camera2 YUV ImageReader.
 *
 * The base exposure remains the rendering reference. A short exposure is blended only into
 * clipped highlights; a long exposure is used conservatively in static deep shadows. Secondary
 * frames are globally and locally aligned and motion-masked before any pixels are fused.
 */
object OppoPhotoHdrEngine {
    private const val ALIGN_W = 256
    private const val ALIGN_H = 192
    private const val ALIGN_SEARCH_RADIUS = 12
    private const val MESH_COLS = 8
    private const val MESH_ROWS = 6
    private const val LOCAL_SEARCH_RADIUS = 4
    private const val MOTION_W = 256
    private const val MOTION_H = 192
    private const val BASE_MOTION_THRESHOLD = 0.055f
    private const val GRADIENT_ADAPTATION = 0.22f

    suspend fun processHdrCapture(
        frames: List<OppoHdrInputFrame>,
        rotationDegrees: Int,
        targetAspectRatio: Float?,
        mirrorOutput: Boolean,
        jpegQuality: Int = 98,
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext null
        val decoded = mutableListOf<Pair<OppoHdrInputFrame, Bitmap>>()
        try {
            for (frame in frames) {
                val bitmap = decodeCameraImage(frame.image) ?: continue
                decoded += frame to bitmap
            }
            if (decoded.isEmpty()) return@withContext null

            val basePair = decoded.firstOrNull { it.first.role == OppoHdrFrameRole.REFERENCE_BASE }
                ?: decoded.first()
            val baseBitmap = basePair.second.copy(Bitmap.Config.ARGB_8888, true)
            if (decoded.size < 2) {
                return@withContext transformOutput(
                    baseBitmap, rotationDegrees, targetAspectRatio, mirrorOutput
                )
            }

            val result = fuseFrames(decoded, basePair, baseBitmap)
            transformOutput(result, rotationDegrees, targetAspectRatio, mirrorOutput)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            android.util.Log.e("OppoPhotoHdrEngine", "Oppo HDR processing failed; using base exposure", t)
            null
        } finally {
            decoded.forEach { (_, bitmap) ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private suspend fun fuseFrames(
        decoded: List<Pair<OppoHdrInputFrame, Bitmap>>,
        basePair: Pair<OppoHdrInputFrame, Bitmap>,
        mutableBase: Bitmap,
    ): Bitmap {
        val baseFrame = basePair.first
        val baseBitmap = basePair.second
        val width = baseBitmap.width
        val height = baseBitmap.height
        val referenceLuma = extractLuminance(baseBitmap, ALIGN_W, ALIGN_H)

        val shortPair = decoded.firstOrNull { it.first.role == OppoHdrFrameRole.SHORT_HIGHLIGHT }
        val longPair = decoded.firstOrNull { it.first.role == OppoHdrFrameRole.LONG_SHADOW }

        val aligned = coroutineScope {
            val shortJob = async(Dispatchers.Default) {
                shortPair?.let { alignSecondary(it.first, it.second, baseFrame, referenceLuma, width, height) }
            }
            val longJob = async(Dispatchers.Default) {
                longPair?.let { alignSecondary(it.first, it.second, baseFrame, referenceLuma, width, height) }
            }
            listOfNotNull(shortJob.await(), longJob.await())
        }
        val short = aligned.firstOrNull { it.role == OppoHdrFrameRole.SHORT_HIGHLIGHT }
            ?.takeIf { it.alignment.isAligned && it.alignment.confidence >= 0.65f }
        val long = aligned.firstOrNull { it.role == OppoHdrFrameRole.LONG_SHADOW }
            ?.takeIf { it.alignment.isAligned && it.alignment.confidence >= 0.70f }

        if (short == null && long == null) return mutableBase

        val coreCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val sliceHeight = (height + coreCount - 1) / coreCount
        val shortMargin = short?.let {
            (max(abs(it.alignment.shiftX), abs(it.alignment.shiftY)) + 32f).toInt()
        } ?: 0
        val longMargin = long?.let {
            (max(abs(it.alignment.shiftX), abs(it.alignment.shiftY)) + 32f).toInt()
        } ?: 0
        val safetyMargin = max(shortMargin, longMargin).coerceIn(16, 128)

        coroutineScope {
            (0 until coreCount).map { sliceIndex ->
                async(Dispatchers.Default) {
                    val startY = sliceIndex * sliceHeight
                    val endY = min(height, (sliceIndex + 1) * sliceHeight)
                    val rows = endY - startY
                    if (rows <= 0) return@async

                    val basePixels = IntArray(width * rows)
                    mutableBase.getPixels(basePixels, 0, width, 0, startY, width, rows)

                    val secondaryStartY = (startY - safetyMargin).coerceAtLeast(0)
                    val secondaryEndY = (endY + safetyMargin).coerceAtMost(height)
                    val secondaryRows = secondaryEndY - secondaryStartY
                    val shortPixels = short?.let {
                        IntArray(width * secondaryRows).also { pixels ->
                            it.bitmap.getPixels(
                                pixels, 0, width, 0, secondaryStartY, width, secondaryRows
                            )
                        }
                    }
                    val longPixels = long?.let {
                        IntArray(width * secondaryRows).also { pixels ->
                            it.bitmap.getPixels(
                                pixels, 0, width, 0, secondaryStartY, width, secondaryRows
                            )
                        }
                    }
                    val rgb = FloatArray(3)
                    val neighborLumas = FloatArray(4)

                    for (localY in 0 until rows) {
                        val y = startY + localY
                        val rowOffset = localY * width
                        val normY = y.toFloat() / (height - 1).coerceAtLeast(1)

                        for (x in 0 until width) {
                            val baseColor = basePixels[rowOffset + x]
                            val baseR = (baseColor shr 16) and 0xFF
                            val baseG = (baseColor shr 8) and 0xFF
                            val baseB = baseColor and 0xFF
                            val normX = x.toFloat() / (width - 1).coerceAtLeast(1)

                            var shortR: Int? = null
                            var shortG: Int? = null
                            var shortB: Int? = null
                            var shortMotion = 0f
                            if (short != null && shortPixels != null) {
                                val displacement = short.alignment.displacement(normX, normY)
                                val tx = (x - displacement.first).toInt()
                                val ty = (y - displacement.second).toInt()
                                if (tx in 0 until width && ty in secondaryStartY until secondaryEndY) {
                                    val c = shortPixels[(ty - secondaryStartY) * width + tx]
                                    shortR = (c shr 16) and 0xFF
                                    shortG = (c shr 8) and 0xFF
                                    shortB = c and 0xFF
                                    shortMotion = short.motionMask.sample(normX, normY)
                                } else {
                                    shortMotion = 1f
                                }
                            }

                            var longR: Int? = null
                            var longG: Int? = null
                            var longB: Int? = null
                            var longMotion = 0f
                            if (long != null && longPixels != null) {
                                val displacement = long.alignment.displacement(normX, normY)
                                val tx = (x - displacement.first).toInt()
                                val ty = (y - displacement.second).toInt()
                                if (tx in 0 until width && ty in secondaryStartY until secondaryEndY) {
                                    val c = longPixels[(ty - secondaryStartY) * width + tx]
                                    longR = (c shr 16) and 0xFF
                                    longG = (c shr 8) and 0xFF
                                    longB = c and 0xFF
                                    longMotion = long.motionMask.sample(normX, normY)
                                } else {
                                    longMotion = 1f
                                }
                            }

                            val edgeDistance = min(x, min(width - 1 - x, min(y, height - 1 - y)))
                            val edgeFeather = (edgeDistance / 48f).coerceIn(0f, 1f)
                            fusePixel(
                                baseR, baseG, baseB,
                                shortR, shortG, shortB,
                                longR, longG, longB,
                                max(shortMotion, longMotion),
                                edgeFeather,
                                rgb,
                            )
                            renderNaturalColor(rgb)

                            if (x in 2 until (width - 2) && localY in 2 until (rows - 2)) {
                                neighborLumas[0] = ((basePixels[rowOffset - width + x] shr 8) and 0xFF) / 255f
                                neighborLumas[1] = ((basePixels[rowOffset + width + x] shr 8) and 0xFF) / 255f
                                neighborLumas[2] = ((basePixels[rowOffset + x + 1] shr 8) and 0xFF) / 255f
                                neighborLumas[3] = ((basePixels[rowOffset + x - 1] shr 8) and 0xFF) / 255f
                                refineDetail(rgb, neighborLumas, baseFrame.captureResult
                                    ?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100)
                            }
                            val r = (rgb[0] * 255f).roundToInt().coerceIn(0, 255)
                            val g = (rgb[1] * 255f).roundToInt().coerceIn(0, 255)
                            val b = (rgb[2] * 255f).roundToInt().coerceIn(0, 255)
                            basePixels[rowOffset + x] =
                                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    mutableBase.setPixels(basePixels, 0, width, 0, startY, width, rows)
                }
            }.awaitAll()
        }
        return mutableBase
    }

    private fun alignSecondary(
        frame: OppoHdrInputFrame,
        bitmap: Bitmap,
        baseFrame: OppoHdrInputFrame,
        referenceLuma: FloatArray,
        width: Int,
        height: Int,
    ): OppoHdrAlignedFrame? {
        if (bitmap.width != width || bitmap.height != height) return null
        val targetLuma = extractLuminance(bitmap, ALIGN_W, ALIGN_H)
        val deltaSeconds = (
            ((frame.captureResult?.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: frame.image.timestamp) -
                (baseFrame.captureResult?.get(CaptureResult.SENSOR_TIMESTAMP)
                    ?: baseFrame.image.timestamp)).toFloat() / 1_000_000_000f
            ).coerceIn(-0.3f, 0.3f)
        val alignment = alignLuminance(referenceLuma, targetLuma, width, height, deltaSeconds)
        val baseProduct = exposureProduct(baseFrame)
        val frameProduct = exposureProduct(frame)
        val exposureRatio = if (baseProduct > 0.0 && frameProduct > 0.0) {
            (baseProduct / frameProduct).toFloat().coerceIn(0.06f, 16f)
        } else {
            2.0.pow(-frame.evOffset.toDouble()).toFloat()
        }
        val motion = detectMotion(
            referenceLuma, targetLuma, frame.evOffset, alignment,
            width, height, exposureRatio,
        )
        return OppoHdrAlignedFrame(
            bitmap = bitmap,
            alignment = alignment,
            motionMask = motion,
            exposureRatio = exposureRatio,
            evOffset = frame.evOffset,
            role = frame.role,
        )
    }

    private fun exposureProduct(frame: OppoHdrInputFrame): Double {
        val exposure = frame.captureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val iso = frame.captureResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        return if (exposure > 0L && iso > 0) exposure.toDouble() * iso.toDouble()
        else 2.0.pow(frame.evOffset.toDouble())
    }

    private fun alignLuminance(
        reference: FloatArray,
        target: FloatArray,
        fullWidth: Int,
        fullHeight: Int,
        deltaSeconds: Float,
    ): OppoHdrAlignment {
        val tileWidth = ALIGN_W / MESH_COLS
        val tileHeight = ALIGN_H / MESH_ROWS
        var minMad = Float.MAX_VALUE
        var bestDx = 0
        var bestDy = 0
        val costGrid = Array(ALIGN_SEARCH_RADIUS * 2 + 1) {
            FloatArray(ALIGN_SEARCH_RADIUS * 2 + 1) { Float.MAX_VALUE }
        }
        val border = ALIGN_SEARCH_RADIUS + 2
        val activeWidth = ALIGN_W - 2 * border
        val activeHeight = ALIGN_H - 2 * border

        for (dy in -ALIGN_SEARCH_RADIUS..ALIGN_SEARCH_RADIUS) {
            for (dx in -ALIGN_SEARCH_RADIUS..ALIGN_SEARCH_RADIUS) {
                var diff = 0f
                var count = 0
                for (y in border until border + activeHeight step 2) {
                    val ty = y + dy
                    if (ty !in 0 until ALIGN_H) continue
                    val refRow = y * ALIGN_W
                    val targetRow = ty * ALIGN_W
                    for (x in border until border + activeWidth step 2) {
                        val tx = x + dx
                        if (tx !in 0 until ALIGN_W) continue
                        diff += abs(reference[refRow + x] - target[targetRow + tx])
                        count++
                    }
                }
                val mad = if (count > 0) diff / count else Float.MAX_VALUE
                costGrid[dy + ALIGN_SEARCH_RADIUS][dx + ALIGN_SEARCH_RADIUS] = mad
                if (mad < minMad) {
                    minMad = mad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        val gridX = bestDx + ALIGN_SEARCH_RADIUS
        val gridY = bestDy + ALIGN_SEARCH_RADIUS
        var subX = 0f
        var subY = 0f
        if (gridX in 1 until ALIGN_SEARCH_RADIUS * 2 &&
            gridY in 1 until ALIGN_SEARCH_RADIUS * 2
        ) {
            val center = costGrid[gridY][gridX]
            val left = costGrid[gridY][gridX - 1]
            val right = costGrid[gridY][gridX + 1]
            val top = costGrid[gridY - 1][gridX]
            val bottom = costGrid[gridY + 1][gridX]
            val denomX = left - 2f * center + right
            val denomY = top - 2f * center + bottom
            if (denomX > 1e-5f) subX = ((left - right) / (2f * denomX)).coerceIn(-0.5f, 0.5f)
            if (denomY > 1e-5f) subY = ((top - bottom) / (2f * denomY)).coerceIn(-0.5f, 0.5f)
        }

        val scaleX = fullWidth.toFloat() / ALIGN_W
        val scaleY = fullHeight.toFloat() / ALIGN_H
        val meshDx = FloatArray(MESH_COLS * MESH_ROWS)
        val meshDy = FloatArray(MESH_COLS * MESH_ROWS)
        val patchRadius = min(tileWidth, tileHeight) / 3

        for (row in 0 until MESH_ROWS) {
            val centerY = row * tileHeight + tileHeight / 2
            for (col in 0 until MESH_COLS) {
                val centerX = col * tileWidth + tileWidth / 2
                var bestLocalX = 0
                var bestLocalY = 0
                var bestLocalMad = Float.MAX_VALUE
                for (localY in -LOCAL_SEARCH_RADIUS..LOCAL_SEARCH_RADIUS) {
                    for (localX in -LOCAL_SEARCH_RADIUS..LOCAL_SEARCH_RADIUS) {
                        val candidateX = bestDx + localX
                        val candidateY = bestDy + localY
                        var diff = 0f
                        var count = 0
                        for (py in -patchRadius..patchRadius step 2) {
                            val ry = centerY + py
                            val ty = ry + candidateY
                            if (ry !in 0 until ALIGN_H || ty !in 0 until ALIGN_H) continue
                            for (px in -patchRadius..patchRadius step 2) {
                                val rx = centerX + px
                                val tx = rx + candidateX
                                if (rx !in 0 until ALIGN_W || tx !in 0 until ALIGN_W) continue
                                diff += abs(reference[ry * ALIGN_W + rx] - target[ty * ALIGN_W + tx])
                                count++
                            }
                        }
                        if (count > 8) {
                            val mad = diff / count
                            if (mad < bestLocalMad) {
                                bestLocalMad = mad
                                bestLocalX = localX
                                bestLocalY = localY
                            }
                        }
                    }
                }
                val index = row * MESH_COLS + col
                meshDx[index] = bestLocalX * scaleX
                meshDy[index] = bestLocalY * scaleY
            }
        }

        val zeroShiftMad = costGrid[ALIGN_SEARCH_RADIUS][ALIGN_SEARCH_RADIUS]
        val withinBounds = abs(bestDx) <= ALIGN_SEARCH_RADIUS && abs(bestDy) <= ALIGN_SEARCH_RADIUS
        val qualityAcceptable = minMad < 0.20f
        val confidence = when {
            !withinBounds || !qualityAcceptable -> 0f
            zeroShiftMad > 1e-4f -> {
                val improvement = ((zeroShiftMad - minMad) / zeroShiftMad).coerceIn(0f, 1f)
                val quality = (1f - minMad * 4f).coerceIn(0f, 1f)
                (0.35f * improvement + 0.65f * quality).coerceIn(0f, 1f)
            }
            else -> (1f - minMad * 4f).coerceIn(0.65f, 1f)
        }
        val aligned = withinBounds && qualityAcceptable && confidence >= 0.65f
        return OppoHdrAlignment(
            shiftX = if (aligned) (bestDx + subX) * scaleX else 0f,
            shiftY = if (aligned) (bestDy + subY) * scaleY else 0f,
            confidence = confidence,
            isAligned = aligned,
            mesh = OppoHdrLocalMesh(MESH_COLS, MESH_ROWS, meshDx, meshDy),
        )
    }

    private fun detectMotion(
        reference: FloatArray,
        target: FloatArray,
        targetEv: Float,
        alignment: OppoHdrAlignment,
        fullWidth: Int,
        fullHeight: Int,
        exposureRatio: Float,
    ): OppoHdrMotionMask {
        val raw = FloatArray(MOTION_W * MOTION_H)
        val mask = FloatArray(MOTION_W * MOTION_H)
        val safeWidth = fullWidth.coerceAtLeast(MOTION_W).toFloat()
        val safeHeight = fullHeight.coerceAtLeast(MOTION_H).toFloat()
        val exposureScale = exposureRatio.takeIf { it.isFinite() && it > 0f }
            ?: 2f.pow(-targetEv)

        for (y in 1 until MOTION_H - 1) {
            val normY = y.toFloat() / (MOTION_H - 1)
            val row = y * MOTION_W
            for (x in 1 until MOTION_W - 1) {
                val normX = x.toFloat() / (MOTION_W - 1)
                val displacement = alignment.displacement(normX, normY)
                val tx = (x - displacement.first * MOTION_W / safeWidth).toInt()
                val ty = (y - displacement.second * MOTION_H / safeHeight).toInt()
                if (tx !in 0 until MOTION_W || ty !in 0 until MOTION_H) {
                    raw[row + x] = 1f
                    continue
                }
                val ref = reference[row + x]
                val adjustedTarget = target[ty * MOTION_W + tx] * exposureScale
                val gradientX = abs(reference[row + x + 1] - reference[row + x - 1]) * 0.5f
                val gradientY = abs(reference[(y + 1) * MOTION_W + x] -
                    reference[(y - 1) * MOTION_W + x]) * 0.5f
                val threshold = BASE_MOTION_THRESHOLD +
                    GRADIENT_ADAPTATION * (gradientX + gradientY)
                val diff = abs(ref - adjustedTarget)
                raw[row + x] = if (diff > threshold) {
                    ((diff - threshold) / (threshold * 1.4f)).coerceIn(0f, 1f)
                } else 0f
            }
        }

        for (y in 1 until MOTION_H - 1) {
            val row = y * MOTION_W
            for (x in 1 until MOTION_W - 1) {
                var sum = 0f
                var maximum = 0f
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val value = raw[(y + dy) * MOTION_W + x + dx]
                        sum += value
                        maximum = max(maximum, value)
                    }
                }
                mask[row + x] = (0.7f * maximum + 0.3f * (sum / 9f)).coerceIn(0f, 1f)
            }
        }
        return OppoHdrMotionMask(MOTION_W, MOTION_H, mask)
    }

    private fun fusePixel(
        baseR: Int, baseG: Int, baseB: Int,
        shortR: Int?, shortG: Int?, shortB: Int?,
        longR: Int?, longG: Int?, longB: Int?,
        motion: Float,
        edgeFeather: Float,
        out: FloatArray,
    ) {
        val br = baseR / 255f
        val bg = baseG / 255f
        val bb = baseB / 255f
        val baseLuma = 0.2126f * br + 0.7152f * bg + 0.0722f * bb
        val maxChannel = max(br, max(bg, bb))
        var red = br
        var green = bg
        var blue = bb
        val staticFactor = (1f - motion).coerceIn(0f, 1f) * edgeFeather.coerceIn(0f, 1f)

        if (shortR != null && shortG != null && shortB != null &&
            staticFactor > 0.15f && maxChannel > 0.82f
        ) {
            val sr = shortR / 255f
            val sg = shortG / 255f
            val sb = shortB / 255f
            val shortLuma = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb
            val blend = smoothstep(0.82f, 0.98f, maxChannel) * staticFactor
            if (blend > 0.01f) {
                val knee = 0.82f / max(0.20f, shortLuma)
                red = br * (1f - blend) + (sr * knee).coerceIn(0f, 1f) * blend
                green = bg * (1f - blend) + (sg * knee).coerceIn(0f, 1f) * blend
                blue = bb * (1f - blend) + (sb * knee).coerceIn(0f, 1f) * blend
            }
        }

        if (longR != null && longG != null && longB != null &&
            staticFactor > 0.35f && baseLuma in 0.02f..0.18f
        ) {
            val lr = longR / 255f
            val lg = longG / 255f
            val lb = longB / 255f
            val blend = (1f - smoothstep(0.02f, 0.18f, baseLuma)) * 0.25f * staticFactor
            if (blend > 0.01f) {
                red = red * (1f - blend) + (lr * 0.5f).coerceIn(0f, 1f) * blend
                green = green * (1f - blend) + (lg * 0.5f).coerceIn(0f, 1f) * blend
                blue = blue * (1f - blend) + (lb * 0.5f).coerceIn(0f, 1f) * blend
            }
        }
        out[0] = red.coerceIn(0f, 1f)
        out[1] = green.coerceIn(0f, 1f)
        out[2] = blue.coerceIn(0f, 1f)
    }

    private fun renderNaturalColor(rgb: FloatArray) {
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = -0.1687f * r - 0.3313f * g + 0.5f * b
        val cr = 0.5f * r - 0.4187f * g - 0.0813f * b
        val skin = cb in -0.22f..-0.03f && cr in 0.07f..0.30f && y in 0.15f..0.85f
        val highlightFactor = if (y > 0.80f) {
            val t = ((y - 0.80f) / 0.20f).coerceIn(0f, 1f)
            1f - 0.55f * t * t
        } else 1f
        val shadowFactor = if (y < 0.12f) (y / 0.12f).coerceIn(0.35f, 1f) else 1f
        val maximum = max(r, max(g, b))
        val minimum = min(r, min(g, b))
        val saturation = if (maximum > 1e-4f) (maximum - minimum) / maximum else 0f
        val vibrance = if (skin) 1f else 1f + 0.12f * (1f - saturation) *
            highlightFactor * shadowFactor
        val multiplier = (vibrance * highlightFactor * shadowFactor).coerceIn(0.2f, 1.25f)
        rgb[0] = (y + (r - y) * multiplier).coerceIn(0f, 1f)
        rgb[1] = (y + (g - y) * multiplier).coerceIn(0f, 1f)
        rgb[2] = (y + (b - y) * multiplier).coerceIn(0f, 1f)
    }

    private fun refineDetail(rgb: FloatArray, neighbors: FloatArray, iso: Int) {
        val center = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
        val average = (neighbors[0] + neighbors[1] + neighbors[2] + neighbors[3]) * 0.25f
        val delta = center - average
        var variance = 0f
        for (value in neighbors) {
            val d = value - average
            variance += d * d
        }
        variance *= 0.25f
        if (variance < 0.003f) return
        val edgeAttenuation = if (variance > 0.08f) 0.45f else 1f
        val isoFactor = if (iso > 800) (800f / iso).coerceIn(0.3f, 1f) else 1f
        val sharpenDelta = (delta * 0.10f * isoFactor * edgeAttenuation)
            .coerceIn(-0.02f, 0.02f)
        rgb[0] = (rgb[0] + sharpenDelta).coerceIn(0f, 1f)
        rgb[1] = (rgb[1] + sharpenDelta).coerceIn(0f, 1f)
        rgb[2] = (rgb[2] + sharpenDelta).coerceIn(0f, 1f)
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun extractLuminance(bitmap: Bitmap, width: Int, height: Int): FloatArray {
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val color = pixels[i]
            output[i] = (
                ((color shr 16) and 0xFF) * 0.2126f +
                    ((color shr 8) and 0xFF) * 0.7152f +
                    (color and 0xFF) * 0.0722f
                ) / 255f
        }
        if (scaled !== bitmap) scaled.recycle()
        return output
    }

    private fun decodeCameraImage(safeImage: SafeImage): Bitmap? {
        val image = safeImage.image
        return when (safeImage.format) {
            ImageFormat.YUV_420_888 -> convertYuv420ToBitmap(image)
            ImageFormat.JPEG -> {
                val buffer = image.planes.firstOrNull()?.buffer?.duplicate() ?: return null
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            else -> null
        }
    }

    private fun convertYuv420ToBitmap(image: Image): Bitmap {
        require(image.planes.size >= 3) { "YUV image has fewer than three planes" }
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val yBase = yBuffer.position()
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()
        val pixels = IntArray(width * height)
        val isHd = width >= 1280 || height >= 720

        fun sample(buffer: java.nio.ByteBuffer, index: Int): Int =
            if (index in 0 until buffer.limit()) buffer.get(index).toInt() and 0xFF else 0

        for (y in 0 until height) {
            val yRow = yBase + y * yPlane.rowStride
            val uvRow = (y shr 1)
            for (x in 0 until width) {
                val yValue = sample(yBuffer, yRow + x * yPlane.pixelStride)
                val uvColumn = (x shr 1)
                val u = sample(uBuffer, uBase + uvRow * uPlane.rowStride +
                    uvColumn * uPlane.pixelStride) - 128
                val v = sample(vBuffer, vBase + uvRow * vPlane.rowStride +
                    uvColumn * vPlane.pixelStride) - 128
                val red: Int
                val green: Int
                val blue: Int
                if (isHd) {
                    red = (yValue + 1.5748f * v).roundToInt()
                    green = (yValue - 0.1873f * u - 0.4681f * v).roundToInt()
                    blue = (yValue + 1.8556f * u).roundToInt()
                } else {
                    red = (yValue + 1.402f * v).roundToInt()
                    green = (yValue - 0.344136f * u - 0.714136f * v).roundToInt()
                    blue = (yValue + 1.772f * u).roundToInt()
                }
                pixels[y * width + x] = (0xFF shl 24) or
                    (red.coerceIn(0, 255) shl 16) or
                    (green.coerceIn(0, 255) shl 8) or
                    blue.coerceIn(0, 255)
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun transformOutput(
        source: Bitmap,
        rotationDegrees: Int,
        targetAspectRatio: Float?,
        mirror: Boolean,
    ): Bitmap {
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val matrix = Matrix().apply {
            if (rotation != 0) postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        var output = if (!matrix.isIdentity) {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                .also { if (it !== source && !source.isRecycled) source.recycle() }
        } else {
            source
        }

        val ratio = targetAspectRatio?.takeIf { it.isFinite() && it > 0f }
        if (ratio != null) {
            val currentRatio = output.width.toFloat() / output.height.toFloat()
            val cropWidth: Int
            val cropHeight: Int
            if (currentRatio > ratio) {
                cropHeight = output.height
                cropWidth = (cropHeight * ratio).roundToInt().coerceIn(1, output.width)
            } else {
                cropWidth = output.width
                cropHeight = (cropWidth / ratio).roundToInt().coerceIn(1, output.height)
            }
            if (cropWidth != output.width || cropHeight != output.height) {
                val left = (output.width - cropWidth) / 2
                val top = (output.height - cropHeight) / 2
                val cropped = Bitmap.createBitmap(output, left, top, cropWidth, cropHeight)
                if (cropped !== output && !output.isRecycled) output.recycle()
                output = cropped
            }
        }
        return output
    }
}
