package com.hinnka.mycamera.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Builds a compact focus-energy depth map from a set of focus-bracket JPEGs.
 * The full-resolution planes remain untouched for the gallery refocus viewer.
 */
object OppoRefocusDepthEngine {
    private const val TARGET_LONG_EDGE = 480

    fun createDepthMap(frameFiles: List<File>, destination: File): Boolean {
        if (frameFiles.size < 3 || frameFiles.any { !it.isFile }) return false
        val optionsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frameFiles.first().absolutePath, optionsBounds)
        val largestEdge = max(optionsBounds.outWidth, optionsBounds.outHeight).coerceAtLeast(1)
        var sampleSize = 1
        while (largestEdge / (sampleSize * 2) >= TARGET_LONG_EDGE) sampleSize *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val energyPlanes = ArrayList<FloatArray>(frameFiles.size)
        var width = 0
        var height = 0
        try {
            for (file in frameFiles) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return false
                try {
                    if (width == 0) {
                        width = bitmap.width
                        height = bitmap.height
                    }
                    if (bitmap.width != width || bitmap.height != height) return false
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    val luma = FloatArray(pixels.size) { index ->
                        val c = pixels[index]
                        (0.2126f * ((c shr 16) and 0xFF) +
                            0.7152f * ((c shr 8) and 0xFF) +
                            0.0722f * (c and 0xFF)) / 255f
                    }
                    val energy = FloatArray(pixels.size)
                    for (y in 1 until height - 1) {
                        val row = y * width
                        for (x in 1 until width - 1) {
                            val index = row + x
                            val laplacian = abs(
                                4f * luma[index] - luma[index - 1] - luma[index + 1] -
                                    luma[index - width] - luma[index + width]
                            )
                            energy[index] = laplacian
                        }
                    }
                    energyPlanes.add(energy)
                } finally {
                    bitmap.recycle()
                }
            }

            val depth = IntArray(width * height)
            val planeCount = energyPlanes.size
            for (index in depth.indices) {
                var bestPlane = planeCount / 2
                var bestEnergy = 0f
                for (plane in 0 until planeCount) {
                    val energy = energyPlanes[plane][index]
                    if (energy > bestEnergy) {
                        bestEnergy = energy
                        bestPlane = plane
                    }
                }
                depth[index] = if (bestEnergy > 0.012f) {
                    (bestPlane.toFloat() / (planeCount - 1).coerceAtLeast(1) * 255f).toInt()
                } else {
                    128
                }
            }

            val depthBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                for (i in depth.indices) {
                    val value = depth[i].coerceIn(0, 255)
                    depth[i] = Color.rgb(value, value, value)
                }
                depthBitmap.setPixels(depth, 0, width, 0, 0, width, height)
                destination.parentFile?.mkdirs()
                FileOutputStream(destination).use {
                    if (!depthBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) return false
                }
                return destination.isFile && destination.length() > 0L
            } finally {
                depthBitmap.recycle()
            }
        } catch (_: Exception) {
            return false
        }
    }
}
