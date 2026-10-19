package com.hinnka.mycamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import com.hinnka.mycamera.utils.PLog
import com.hinnka.mycamera.video.CaptureMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Keeps the inactive rear lens genuinely streaming, rather than showing a fake readiness flag.
 * Sensors on one logical CameraDevice use a physical OutputConfiguration in the active session.
 * Separate CameraDevices are warmed only for Android-declared concurrent combinations.
 * PRIVATE ImageReader outputs are drained continuously so their buffer queues cannot stall.
 */
internal class BackgroundUltraWideReady(context: Context) {
    data class PreparedPhysicalOutput internal constructor(
        val surface: Surface,
        val physicalCameraId: String,
        internal val profileKey: String,
        val size: Size
    )
    private data class PairInfo(
        val activeOpenId: String,
        val standbyOpenId: String,
        val standbyPhysicalId: String?
    )

    companion object {
        private const val TAG = "BackgroundUltraWideReady"
        private const val MAX_IMAGES = 3
    }

    private val manager = context.applicationContext
        .getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread(TAG).apply { start() }
    private val handler = Handler(thread.looper)
    private val generation = AtomicInteger()
    private val lock = Any()

    @Volatile private var enabled = false
    @Volatile private var standbyDevice: CameraDevice? = null
    @Volatile private var standbySession: CameraCaptureSession? = null
    @Volatile private var standbyReader: ImageReader? = null
    @Volatile private var standbyOpenId: String? = null
    @Volatile private var activeOpenId: String? = null
    @Volatile private var concurrentProfileKey: String? = null

    private var physicalReader: ImageReader? = null
    private var physicalProfileKey: String? = null
    private var physicalAttached = false
    private val retiredPhysicalReaders = mutableListOf<ImageReader>()
    private val rejectedPhysicalProfiles = mutableSetOf<String>()
    private val loggedFrameProfiles = mutableSetOf<String>()

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) stopIndependentStream()
    }

    fun hasPreparedPhysicalOutput(): Boolean = synchronized(lock) { physicalReader != null }

    /** Prepares a real output for the inactive physical sensor before the active session opens. */
    fun preparePhysicalOutput(
        cameras: List<CameraInfo>,
        currentCameraId: String,
        currentLensType: LensType,
        activeOpenCameraId: String?,
        requestedSize: Size,
        captureMode: CaptureMode
    ): PreparedPhysicalOutput? {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val pair = resolvePair(cameras, currentCameraId, currentLensType, activeOpenCameraId) ?: return null
        val physicalId = pair.standbyPhysicalId ?: return null
        if (pair.activeOpenId == physicalId) return null

        val activeChars = try {
            manager.getCameraCharacteristics(pair.activeOpenId)
        } catch (e: Exception) {
            PLog.w(TAG, "Cannot inspect active camera for physical standby: " + e.message)
            return null
        }
        if (!activeChars.physicalCameraIds.contains(physicalId)) return null

        val standbyChars = try {
            manager.getCameraCharacteristics(physicalId)
        } catch (e: Exception) {
            PLog.w(TAG, "Cannot inspect standby physical camera: " + e.message)
            return null
        }
        val sizes = privateSizes(standbyChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        if (sizes.isEmpty()) {
            PLog.w(TAG, "Standby physical sensor exposes no PRIVATE stream sizes: " + physicalId)
            return null
        }
        val size = chooseSize(sizes, requestedSize)
        val key = pair.activeOpenId + "|" + physicalId + "|" +
            size.width + "x" + size.height + "|" + captureMode.name

        stopIndependentStream()
        synchronized(lock) {
            if (key in rejectedPhysicalProfiles) return null
            val current = physicalReader
            if (current != null && physicalProfileKey == key) {
                return PreparedPhysicalOutput(current.surface, physicalId, key, size)
            }
            if (current != null) retiredPhysicalReaders.add(current)
            val reader = try {
                newDrainingReader(size, "physical", key)
            } catch (e: Exception) {
                PLog.w(TAG, "Cannot allocate physical standby reader: " + e.message)
                return null
            }
            physicalReader = reader
            physicalProfileKey = key
            physicalAttached = false
            PLog.i(TAG, "Prepared shared-camera standby output: sensor=" + physicalId +
                ", size=" + size.width + "x" + size.height + ", mode=" + captureMode.name)
            return PreparedPhysicalOutput(reader.surface, physicalId, key, size)
        }
    }

    /** Old readers are released only after the replacement active-camera session is configured. */
    fun onMainSessionConfigured(output: PreparedPhysicalOutput?) {
        synchronized(lock) {
            physicalAttached = output != null &&
                physicalReader?.surface === output.surface && physicalProfileKey == output.profileKey
            retiredPhysicalReaders.forEach(::closeReader)
            retiredPhysicalReaders.clear()
            if (!physicalAttached) {
                physicalReader?.let(::closeReader)
                physicalReader = null
                physicalProfileKey = null
            }
        }
    }

    /** Reject an optional stream profile after the HAL refuses it; normal preview can then retry. */
    fun markPhysicalOutputConfigurationFailed(output: PreparedPhysicalOutput): Boolean {
        synchronized(lock) {
            if (!rejectedPhysicalProfiles.add(output.profileKey)) return false
            if (physicalReader?.surface === output.surface) {
                physicalReader?.let(::closeReader)
                physicalReader = null
                physicalProfileKey = null
            }
            physicalAttached = false
            retiredPhysicalReaders.forEach(::closeReader)
            retiredPhysicalReaders.clear()
        }
        PLog.w(TAG, "HAL rejected optional physical standby stream; retrying without it")
        return true
    }

    /** Warm a separate standby CameraDevice only if Android exposes a concurrent pair. */
    fun update(
        cameras: List<CameraInfo>,
        currentCameraId: String,
        currentLensType: LensType,
        activeOpenCameraId: String?,
        requestedSize: Size
    ) {
        handler.post {
            if (!enabled) {
                releaseIndependentLocked()
                return@post
            }
            val pair = resolvePair(cameras, currentCameraId, currentLensType, activeOpenCameraId)
            if (pair == null) {
                releaseIndependentLocked()
                return@post
            }
            val physicalId = pair.standbyPhysicalId
            if (physicalId != null && isPhysicalAttached(pair.activeOpenId, physicalId)) {
                releaseIndependentLocked()
                return@post
            }
            if (pair.activeOpenId == pair.standbyOpenId) {
                releaseIndependentLocked()
                PLog.d(TAG, "Standby shares active CameraDevice but its physical output was not configured")
                return@post
            }
            if (!isConcurrentPair(pair.activeOpenId, pair.standbyOpenId)) {
                releaseIndependentLocked()
                PLog.d(TAG, "Standby skipped; Android does not declare this camera pair concurrent")
                return@post
            }

            val prepared = try {
                createReaderForCamera(pair.standbyOpenId, requestedSize)
            } catch (e: Exception) {
                PLog.w(TAG, "Cannot prepare concurrent standby reader: " + e.message)
                releaseIndependentLocked()
                return@post
            } ?: run {
                releaseIndependentLocked()
                PLog.w(TAG, "No supported PRIVATE stream size for standby camera " + pair.standbyOpenId)
                return@post
            }

            val reader = prepared.first
            val size = prepared.second
            val key = pair.activeOpenId + "|" + pair.standbyOpenId + "|" +
                size.width + "x" + size.height
            if (standbyDevice != null && standbySession != null &&
                standbyOpenId == pair.standbyOpenId && activeOpenId == pair.activeOpenId &&
                concurrentProfileKey == key
            ) {
                closeReader(reader)
                return@post
            }

            releaseIndependentLocked()
            val token = generation.incrementAndGet()
            standbyReader = reader
            standbyOpenId = pair.standbyOpenId
            activeOpenId = pair.activeOpenId
            concurrentProfileKey = key
            PLog.i(TAG, "Opening Android-declared concurrent standby stream: " +
                pair.standbyOpenId + " at " + size.width + "x" + size.height)
            try {
                @SuppressLint("MissingPermission")
                manager.openCamera(pair.standbyOpenId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (generation.get() != token || !enabled ||
                            standbyOpenId != pair.standbyOpenId || activeOpenId != pair.activeOpenId
                        ) {
                            camera.close()
                            return
                        }
                        standbyDevice = camera
                        try {
                            camera.createCaptureSession(listOf(reader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        if (generation.get() != token || standbyDevice !== camera || !enabled) {
                                            session.close()
                                            return
                                        }
                                        try {
                                            val request = camera.createCaptureRequest(
                                                CameraDevice.TEMPLATE_PREVIEW
                                            ).apply { addTarget(reader.surface) }.build()
                                            session.setRepeatingRequest(request, null, handler)
                                            standbySession = session
                                            PLog.i(TAG, "Concurrent standby repeating stream active: " +
                                                pair.standbyOpenId + " " + size.width + "x" + size.height)
                                        } catch (e: Exception) {
                                            PLog.w(TAG, "Standby repeating request failed: " + e.message)
                                            session.close()
                                            camera.close()
                                            releaseIndependentForToken(token)
                                        }
                                    }
                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        session.close()
                                        camera.close()
                                        releaseIndependentForToken(token)
                                        PLog.w(TAG, "Concurrent standby session configuration failed")
                                    }
                                    override fun onClosed(session: CameraCaptureSession) {
                                        if (standbySession === session) standbySession = null
                                    }
                                }, handler)
                        } catch (e: Exception) {
                            PLog.w(TAG, "Cannot configure concurrent standby: " + e.message)
                            camera.close()
                            releaseIndependentForToken(token)
                        }
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        releaseIndependentForToken(token)
                        PLog.w(TAG, "Concurrent standby camera disconnected")
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        releaseIndependentForToken(token)
                        PLog.w(TAG, "Concurrent standby camera error=" + error)
                    }
                }, handler)
            } catch (e: Exception) {
                PLog.w(TAG, "Concurrent standby open failed: " + e.message)
                releaseIndependentForToken(token)
            }
        }
    }

    fun stopIndependentStream() {
        if (Looper.myLooper() == handler.looper) releaseIndependentLocked()
        else handler.post { releaseIndependentLocked() }
    }

    /** Called on active-camera shutdown; no standby resource survives that lifecycle. */
    fun stop() {
        handler.post {
            releaseIndependentLocked()
            synchronized(lock) {
                physicalReader?.let(::closeReader)
                physicalReader = null
                physicalProfileKey = null
                physicalAttached = false
                retiredPhysicalReaders.forEach(::closeReader)
                retiredPhysicalReaders.clear()
            }
        }
    }

    private fun resolvePair(
        cameras: List<CameraInfo>,
        currentCameraId: String,
        currentLensType: LensType,
        activeOpenCameraId: String?
    ): PairInfo? {
        val active = cameras.firstOrNull {
            it.cameraId == currentCameraId && it.lensType == currentLensType
        } ?: cameras.firstOrNull { it.cameraId == currentCameraId }
            ?: cameras.firstOrNull {
                it.lensType == currentLensType &&
                    (activeOpenCameraId == null || safeOpenId(it) == activeOpenCameraId)
            } ?: return null
        if (active.lensFacing != CameraCharacteristics.LENS_FACING_BACK) return null
        val wanted = if (active.lensType == LensType.BACK_ULTRA_WIDE) {
            LensType.BACK_MAIN
        } else {
            LensType.BACK_ULTRA_WIDE
        }
        val standby = cameras.firstOrNull {
            it.lensType == wanted && it.lensFacing == CameraCharacteristics.LENS_FACING_BACK
        } ?: return null

        val activeId = activeOpenCameraId?.takeIf { it.isNotBlank() } ?: safeOpenId(active)
        val standbyId = safeOpenId(standby)
        val physicalIds = try {
            manager.getCameraCharacteristics(activeId).physicalCameraIds
        } catch (_: Exception) {
            emptySet<String>()
        }
        val candidates = buildList {
            standby.outputPhysicalCameraId?.let { add(it) }
            add(standby.cameraId)
            standby.baseCameraId?.let { add(it) }
            standby.physicalCameras.forEach { add(it.cameraId) }
        }.distinct()
        val physicalId = candidates.firstOrNull { it in physicalIds && it != active.outputPhysicalCameraId }
        return PairInfo(activeId, standbyId, physicalId)
    }

    private fun safeOpenId(info: CameraInfo): String = try {
        info.getOpenCameraId()
    } catch (_: RuntimeException) {
        info.logicalCameraId ?: info.baseCameraId ?: info.cameraId
    }

    private fun isPhysicalAttached(activeId: String, physicalId: String): Boolean =
        synchronized(lock) {
            physicalAttached &&
                physicalProfileKey?.startsWith(activeId + "|" + physicalId + "|") == true
        }

    private fun isConcurrentPair(activeId: String, standbyId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || activeId == standbyId) return false
        return try {
            manager.concurrentCameraIds.any { it.contains(activeId) && it.contains(standbyId) }
        } catch (e: Exception) {
            PLog.w(TAG, "Cannot query concurrent camera combinations: " + e.message)
            false
        }
    }

    private fun createReaderForCamera(cameraId: String, requested: Size): Pair<ImageReader, Size>? {
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = privateSizes(map)
        if (sizes.isEmpty()) return null
        val size = chooseSize(sizes, requested)
        return newDrainingReader(size, "concurrent", cameraId + "|" + size.width + "x" + size.height) to size
    }

    private fun privateSizes(map: StreamConfigurationMap?): List<Size> =
        map?.getOutputSizes(ImageFormat.PRIVATE)?.toList().orEmpty()

    private fun chooseSize(sizes: List<Size>, requested: Size): Size {
        sizes.firstOrNull { it.width == requested.width && it.height == requested.height }?.let { return it }
        val area = requested.width.toLong() * requested.height.toLong()
        val ratio = requested.width.toDouble() / requested.height.coerceAtLeast(1)
        val candidates = sizes.filter {
            abs(it.width.toDouble() / it.height.coerceAtLeast(1) - ratio) <= 0.025
        }.ifEmpty { sizes }
        return candidates.filter { it.width.toLong() * it.height.toLong() <= area }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: candidates.minByOrNull { abs(it.width.toLong() * it.height.toLong() - area) }
            ?: sizes.first()
    }

    private fun newDrainingReader(size: Size, kind: String, profile: String): ImageReader {
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, MAX_IMAGES)
        reader.setOnImageAvailableListener({ source ->
            var gotFrame = false
            try {
                source.acquireLatestImage()?.let { image ->
                    image.close()
                    gotFrame = true
                }
            } catch (e: Exception) {
                PLog.d(TAG, "Standby frame draining stopped: " + e.message)
            }
            if (gotFrame) {
                val current = synchronized(lock) {
                    val isActive = if (kind == "physical") {
                        physicalReader === source
                    } else {
                        standbyReader === source
                    }
                    isActive && loggedFrameProfiles.add(profile)
                }
                if (current) PLog.i(TAG, "Actual standby frames received: " + kind + ", " + profile)
            }
        }, handler)
        return reader
    }

    private fun closeReader(reader: ImageReader) {
        try { reader.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}
    }

    private fun releaseIndependentForToken(token: Int) {
        if (generation.get() == token) releaseIndependentLocked()
    }
    private fun releaseIndependentLocked() {
        generation.incrementAndGet()
        try { standbySession?.stopRepeating() } catch (_: Exception) {}
        try { standbySession?.close() } catch (_: Exception) {}
        standbySession = null
        try { standbyDevice?.close() } catch (_: Exception) {}
        standbyDevice = null
        standbyReader?.let(::closeReader)
        standbyReader = null
        standbyOpenId = null
        activeOpenId = null
        concurrentProfileKey = null
    }
}
