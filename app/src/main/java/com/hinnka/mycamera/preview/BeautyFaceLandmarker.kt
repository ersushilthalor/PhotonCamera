package com.hinnka.mycamera.preview

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.hinnka.mycamera.lut.BeautyFaceRegion
import com.hinnka.mycamera.utils.PLog
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs Face Landmarker away from the camera and GL threads. Inputs are low-resolution preview
 * copies; the returned landmark region is consumed by the GPU beauty shader.
 */
class BeautyFaceLandmarker(
    context: Context,
    private val onRegionChanged: (BeautyFaceRegion?) -> Unit,
) : Closeable {
    companion object {
        private const val TAG = "BeautyFaceLandmarker"
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val MAX_INPUT_EDGE = 480
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PhotonBeautyLandmarker").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val frameInFlight = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private var landmarker: FaceLandmarker? = null

    fun submit(input: Bitmap) {
        if (released.get() || !frameInFlight.compareAndSet(false, true)) {
            input.recycleIfAlive()
            return
        }
        try {
            executor.execute {
                try {
                    detect(input)
                } catch (error: RuntimeException) {
                    PLog.e(TAG, "Face landmark inference failed", error)
                    onRegionChanged(null)
                } finally {
                    input.recycleIfAlive()
                    frameInFlight.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            input.recycleIfAlive()
            frameInFlight.set(false)
        }
    }

    private fun detect(bitmap: Bitmap) {
        if (released.get()) return
        val task = landmarker ?: createLandmarker().also { landmarker = it }
        val image = BitmapImageBuilder(bitmap).build()
        try {
            val result = task.detect(image)
            val firstFace = result.faceLandmarks().firstOrNull()
            val points = firstFace?.map { landmark ->
                landmark.x().coerceIn(0f, 1f) to landmark.y().coerceIn(0f, 1f)
            }
            onRegionChanged(points?.let(BeautyFaceRegion::fromLandmarks))
        } finally {
            image.close()
        }
    }

    private fun createLandmarker(): FaceLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.CPU)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.55f)
            .setMinFacePresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .build()
        return FaceLandmarker.createFromOptions(appContext, options).also {
            PLog.i(TAG, "MediaPipe Face Landmarker initialized")
        }
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        executor.execute {
            try {
                landmarker?.close()
            } catch (error: RuntimeException) {
                PLog.w(TAG, "Error closing Face Landmarker: ${error.message}")
            } finally {
                landmarker = null
            }
        }
        executor.shutdown()
        onRegionChanged(null)
    }
}

private fun Bitmap.recycleIfAlive() {
    if (!isRecycled) recycle()
}
