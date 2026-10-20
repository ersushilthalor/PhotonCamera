package com.hinnka.mycamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import com.hinnka.mycamera.utils.PLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps the non-active rear camera actively streaming in the background.
 *
 * Frames are drained from a real Camera2 output surface so the capture pipeline stays running;
 * this is not a timer or UI-only state. When the warmed camera is selected, the CameraDevice can
 * be handed to Camera2Controller and its preview session recreated on the real viewfinder surface.
 *
 * Standby opening is restricted to Android-declared concurrent camera combinations. Some OEM
 * camera stacks permit more combinations, but probing those blindly can disconnect the active
 * camera, so unsupported pairs are skipped and normal camera opening remains the fallback.
 */
internal class BackgroundUltraWideReady(
    context: Context,
    private val onTransferredDeviceClosed: (CameraDevice) -> Unit,
    private val onTransferredDeviceError: (CameraDevice, String) -> Unit,
) {
    companion object {
        private const val TAG = "BackgroundUltraWideReady"
        private const val CAMERA_CLOSE_WAIT_MS = 2_500L
        private const val ACQUIRE_OPERATION_WAIT_MS = 2_000L
    }

    private data class ForegroundAcquireResult(
        val device: CameraDevice? = null,
        val closingLatch: CountDownLatch? = null,
    )

    private val appContext = context.applicationContext
    private val cameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("BackgroundUltraWideReady").apply { start() }
    private val handler = Handler(thread.looper)
    private val generation = AtomicInteger(0)
    private val frameReady = AtomicBoolean(false)
    private val closeSignals = ConcurrentHashMap<CameraDevice, CountDownLatch>()

    @Volatile private var standbyDevice: CameraDevice? = null
    @Volatile private var standbySession: CameraCaptureSession? = null
    @Volatile private var standbyReader: ImageReader? = null
    @Volatile private var standbyCameraId: String? = null
    @Volatile private var standbyOutputSize: Size? = null
    @Volatile private var activeCameraId: String? = null
    @Volatile private var standbyOpenPending = false
    @Volatile private var standbyOpenLatch: CountDownLatch? = null
    @Volatile private var standbyOpenGeneration: Int = -1

    // A handed-off device is now owned by Camera2Controller, but Android continues to dispatch
    // its callbacks to this class's original StateCallback. Route those terminal events back.
    @Volatile private var transferredDevice: CameraDevice? = null
    @Volatile private var pendingWarmupAfterClose: (() -> Unit)? = null

    fun update(
        cameras: List<CameraInfo>,
        currentCameraId: String,
        activeOpenCameraId: String?,
        targetSize: Size,
    ) {
        handler.post {
            val active = cameras.firstOrNull {
                it.cameraId == currentCameraId ||
                    it.logicalCameraId == activeOpenCameraId ||
                    it.getOpenCameraId() == activeOpenCameraId
            }
            val ultraWide = cameras.firstOrNull { it.lensType == LensType.BACK_ULTRA_WIDE }
            val main = cameras.firstOrNull { it.lensType == LensType.BACK_MAIN }
            val standby = if (active?.lensType == LensType.BACK_ULTRA_WIDE) main else ultraWide
            val activeId = activeOpenCameraId ?: active?.getOpenCameraId()

            if (active == null || standby == null || activeId.isNullOrBlank()) {
                releaseLocked()
                return@post
            }

            val standbyId = try {
                standby.getOpenCameraId()
            } catch (_: RuntimeException) {
                standby.logicalCameraId ?: standby.cameraId
            }
            if (standbyId == activeId) {
                // Two physical lenses under one logical CameraDevice need physical
                // OutputConfigurations on the active session. A second openCamera call is wrong.
                releaseLocked()
                PLog.d(TAG, "Standby skipped: selected lenses share CameraDevice $activeId")
                return@post
            }

            val concurrentPairSupported =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    try {
                        cameraManager.concurrentCameraIds.any { ids ->
                            ids.contains(activeId) && ids.contains(standbyId)
                        }
                    } catch (error: Exception) {
                        PLog.w(TAG, "Unable to query concurrent camera combinations: ${error.message}")
                        false
                    }
            if (!concurrentPairSupported) {
                releaseLocked()
                PLog.d(
                    TAG,
                    "Background lens warm-up skipped: pair is not declared concurrent " +
                        "(active=$activeId, standby=$standbyId)"
                )
                return@post
            }

            val resolvedOutput = try {
                chooseStandbyOutput(standbyId, targetSize)
            } catch (error: Exception) {
                PLog.w(TAG, "Cannot resolve standby output for $standbyId: ${error.message}")
                null
            }
            if (resolvedOutput == null) {
                releaseLocked()
                return@post
            }
            val (format, chosenSize) = resolvedOutput

            // Do not overlap two opens when a previous CameraManager.openCamera callback is still
            // outstanding or its CameraDevice has just been asked to close.
            if (standbyOpenPending) {
                if (
                    standbyOpenGeneration == generation.get() &&
                    standbyCameraId == standbyId &&
                    activeCameraId == activeId &&
                    standbyOutputSize == chosenSize
                ) {
                    return@post
                }
                pendingWarmupAfterClose = {
                    update(cameras, currentCameraId, activeOpenCameraId, targetSize)
                }
                releaseLocked(preservePendingWarmup = true)
                return@post
            }
            if (closeSignals.keys.any { it.id == standbyId }) {
                pendingWarmupAfterClose = {
                    update(cameras, currentCameraId, activeOpenCameraId, targetSize)
                }
                return@post
            }

            if (
                standbyCameraId == standbyId &&
                activeCameraId == activeId &&
                standbyOutputSize == chosenSize &&
                (standbyDevice != null || standbyOpenPending)
            ) {
                if (standbyOpenPending || standbySession != null) return@post
                // Resolution changed (for example 1080p -> 4K). Wait for CameraDevice.onClosed
                // before reopening this same ID; reopening it immediately can race camera-service
                // ownership and leave the standby stream silently unavailable.
                pendingWarmupAfterClose = {
                    update(cameras, currentCameraId, activeOpenCameraId, targetSize)
                }
                releaseLocked(CountDownLatch(1), preservePendingWarmup = true)
                return@post
            }

            pendingWarmupAfterClose = null
            releaseLocked()
            activeCameraId = activeId
            val token = generation.incrementAndGet()
            frameReady.set(false)
            try {
                val reader = ImageReader.newInstance(
                    chosenSize.width,
                    chosenSize.height,
                    format,
                    2,
                )
                standbyReader = reader
                standbyCameraId = standbyId
                standbyOutputSize = chosenSize
                reader.setOnImageAvailableListener({ availableReader ->
                    try {
                        // acquireLatestImage discards queued older buffers and close returns the
                        // buffer immediately, keeping a real repeating stream without CPU decode.
                        val image = availableReader.acquireLatestImage()
                        if (image != null) {
                            image.close()
                            if (
                                generation.get() == token &&
                                standbyReader === availableReader &&
                                frameReady.compareAndSet(false, true)
                            ) {
                                PLog.i(
                                    TAG,
                                    "Standby first frame received: cameraId=$standbyId, " +
                                        "size=${chosenSize.width}x${chosenSize.height}, format=$format"
                                )
                            }
                        }
                    } catch (error: Exception) {
                        if (generation.get() == token) {
                            PLog.w(TAG, "Could not drain standby frame: ${error.message}")
                        }
                    }
                }, handler)

                val openCallbackLatch = CountDownLatch(1)
                standbyOpenPending = true
                standbyOpenLatch = openCallbackLatch
                standbyOpenGeneration = token
                @SuppressLint("MissingPermission")
                cameraManager.openCamera(standbyId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (standbyOpenLatch === openCallbackLatch) {
                            standbyOpenPending = false
                            standbyOpenLatch = null
                            standbyOpenGeneration = -1
                        }
                        openCallbackLatch.countDown()
                        if (generation.get() != token || activeCameraId != activeId) {
                            closeSignals.putIfAbsent(camera, CountDownLatch(1))
                            camera.close()
                            return
                        }
                        standbyDevice = camera
                        try {
                            camera.createCaptureSession(
                                listOf(reader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        if (generation.get() != token || standbyDevice !== camera) {
                                            session.close()
                                            return
                                        }
                                        try {
                                            val request = camera
                                                .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                                .apply { addTarget(reader.surface) }
                                                .build()
                                            session.setRepeatingRequest(request, null, handler)
                                            standbySession = session
                                            PLog.i(
                                                TAG,
                                                "Standby session configured: cameraId=$standbyId, " +
                                                    "size=${chosenSize.width}x${chosenSize.height}; waiting for first frame"
                                            )
                                        } catch (error: Exception) {
                                            PLog.w(TAG, "Could not start standby repeating stream: ${error.message}")
                                            session.close()
                                            camera.close()
                                            releaseResourcesForToken(token)
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        session.close()
                                        camera.close()
                                        releaseResourcesForToken(token)
                                        PLog.w(TAG, "Standby session configuration failed for $standbyId")
                                    }

                                    override fun onClosed(session: CameraCaptureSession) {
                                        if (standbySession === session) standbySession = null
                                    }
                                },
                                handler,
                            )
                        } catch (error: Exception) {
                            PLog.w(TAG, "Could not configure standby camera $standbyId: ${error.message}")
                            camera.close()
                            releaseResourcesForToken(token)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        if (standbyOpenLatch === openCallbackLatch) {
                            standbyOpenPending = false
                            standbyOpenLatch = null
                            standbyOpenGeneration = -1
                        }
                        openCallbackLatch.countDown()
                        if (transferredDevice === camera) {
                            onTransferredDeviceError(camera, "Transferred standby camera disconnected: ${camera.id}")
                            return
                        }
                        closeSignals.putIfAbsent(camera, CountDownLatch(1))
                        camera.close()
                        releaseResourcesForToken(token)
                        PLog.w(TAG, "Standby camera disconnected: $standbyId")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        if (standbyOpenLatch === openCallbackLatch) {
                            standbyOpenPending = false
                            standbyOpenLatch = null
                            standbyOpenGeneration = -1
                        }
                        openCallbackLatch.countDown()
                        if (transferredDevice === camera) {
                            onTransferredDeviceError(
                                camera,
                                "Transferred standby camera error: id=${camera.id}, code=$error"
                            )
                            return
                        }
                        closeSignals.putIfAbsent(camera, CountDownLatch(1))
                        camera.close()
                        releaseResourcesForToken(token)
                        PLog.w(TAG, "Standby camera error: id=$standbyId, code=$error")
                    }

                    override fun onClosed(camera: CameraDevice) {
                        val closingSignal = closeSignals.remove(camera)
                        closingSignal?.countDown()
                        if (transferredDevice === camera) {
                            transferredDevice = null
                            onTransferredDeviceClosed(camera)
                        }
                        if (standbyDevice == null && standbyCameraId == camera.id) {
                            standbyCameraId = null
                            standbyOutputSize = null
                            activeCameraId = null
                            frameReady.set(false)
                        }
                        if (closingSignal != null) {
                            val restart = pendingWarmupAfterClose
                            if (restart != null) {
                                pendingWarmupAfterClose = null
                                restart.invoke()
                            }
                        }
                    }
                }, handler)
            } catch (error: Exception) {
                standbyOpenPending = false
                standbyOpenLatch?.countDown()
                standbyOpenLatch = null
                standbyOpenGeneration = -1
                PLog.w(TAG, "Background lens warm-up failed safely: ${error.message}")
                releaseLocked()
            }
        }
    }

    /**
     * Called by Camera2Controller on its camera thread just before foreground open.
     * A warmed device is reused only after its session is configured and a real frame arrived.
     * If it cannot be reused, release it and wait for CameraDevice.onClosed before trying a fresh
     * open, avoiding an ERROR_MAX_CAMERAS_IN_USE race when switching to another lens.
     */
    fun acquireForForeground(cameraId: String): CameraDevice? {
        val openingLatch = standbyOpenLatch
        if (standbyOpenPending && openingLatch != null) {
            try {
                openingLatch.await(ACQUIRE_OPERATION_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val operation = FutureTask {
            val device = standbyDevice
            if (
                device != null &&
                standbyCameraId == cameraId &&
                standbySession != null &&
                frameReady.get()
            ) {
                val session = standbySession
                try { session?.stopRepeating() } catch (_: Exception) {}
                try { session?.close() } catch (_: Exception) {}
                standbySession = null
                standbyDevice = null
                // The session/reader are no longer needed; controller will configure the active
                // viewfinder and all capture/record outputs for the chosen mode.
                try { standbyReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
                try { standbyReader?.close() } catch (_: Exception) {}
                standbyReader = null
                standbyCameraId = null
                standbyOutputSize = null
                activeCameraId = null
                frameReady.set(false)
                generation.incrementAndGet()
                transferredDevice = device
                PLog.i(TAG, "Reusing warmed CameraDevice for foreground lens: ${device.id}")
                ForegroundAcquireResult(device = device)
            } else {
                // A foreground request takes precedence over a queued size-change warm-up.
                pendingWarmupAfterClose = null
                val closingLatch = if (device != null) {
                    closeSignals[device] ?: CountDownLatch(1)
                } else {
                    closeSignals.values.firstOrNull()
                }
                releaseLocked(closingLatch)
                ForegroundAcquireResult(
                    closingLatch = if (device != null) closeSignals[device] ?: closingLatch else closingLatch
                )
            }
        }
        handler.post(operation)

        val result = try {
            operation.get(ACQUIRE_OPERATION_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            PLog.w(TAG, "Standby handoff check failed safely: ${error.message}")
            return null
        }
        result.device?.let { return it }
        val closingLatch = result.closingLatch
        if (closingLatch != null && !closingLatch.await(CAMERA_CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            PLog.w(TAG, "Standby camera did not close within ${CAMERA_CLOSE_WAIT_MS}ms; normal open will be attempted")
        }
        return null
    }

    fun stop() {
        handler.post { releaseLocked() }
    }

    private fun chooseStandbyOutput(cameraId: String, targetSize: Size): Pair<Int, Size>? {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val formats = map.outputFormats.toSet()
        // PRIVATE supports full-speed opaque buffering without CPU-side YUV conversion. Prefer it
        // for a stream whose pixels are discarded, then fall back to a drainable YUV surface.
        val candidateFormats = listOf(ImageFormat.PRIVATE, ImageFormat.YUV_420_888)
            .filter { it in formats }
        for (format in candidateFormats) {
            val sizes = map.getOutputSizes(format).orEmpty()
            val selected = chooseClosestSize(sizes, targetSize)
            if (selected != null) return format to selected
        }
        return null
    }

    private fun chooseClosestSize(sizes: Array<out Size>, target: Size): Size? {
        if (sizes.isEmpty()) return null
        val wantedWidth = target.width.coerceAtLeast(1)
        val wantedHeight = target.height.coerceAtLeast(1)
        val targetPixels = wantedWidth.toLong() * wantedHeight.toLong()
        val targetAspect = max(wantedWidth, wantedHeight).toFloat() / min(wantedWidth, wantedHeight).toFloat()
        return sizes.minByOrNull { candidate ->
            val candidatePixels = candidate.width.toLong() * candidate.height.toLong()
            val candidateAspect =
                max(candidate.width, candidate.height).toFloat() / min(candidate.width, candidate.height).toFloat()
            val aspectPenalty = (abs(candidateAspect - targetAspect) * targetPixels * 2.0).toLong()
            val areaPenalty = abs(candidatePixels - targetPixels)
            aspectPenalty + areaPenalty
        }
    }

    private fun releaseResourcesForToken(token: Int) {
        if (generation.get() != token) return
        releaseLocked()
    }

    private fun releaseLocked(
        closingLatch: CountDownLatch? = null,
        preservePendingWarmup: Boolean = false,
    ) {
        if (!preservePendingWarmup) pendingWarmupAfterClose = null
        generation.incrementAndGet()
        val deviceToClose = standbyDevice
        val openStillPending = deviceToClose == null && standbyOpenPending
        if (!openStillPending) {
            standbyOpenPending = false
            standbyOpenLatch?.countDown()
            standbyOpenLatch = null
            standbyOpenGeneration = -1
        }
        if (deviceToClose != null) {
            val signal = closeSignals[deviceToClose] ?: closingLatch ?: CountDownLatch(1)
            closeSignals.putIfAbsent(deviceToClose, signal)
        }
        try { standbySession?.stopRepeating() } catch (_: Exception) {}
        try { standbySession?.close() } catch (_: Exception) {}
        standbySession = null
        try { deviceToClose?.close() } catch (_: Exception) {}
        standbyDevice = null
        try { standbyReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { standbyReader?.close() } catch (_: Exception) {}
        standbyReader = null
        if (!openStillPending) {
            standbyCameraId = null
            standbyOutputSize = null
            activeCameraId = null
        }
        frameReady.set(false)
    }
}
