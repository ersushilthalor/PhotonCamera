package com.hinnka.mycamera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import com.hinnka.mycamera.utils.PLog
import com.hinnka.mycamera.video.CaptureMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import kotlin.math.abs

data class AdoptedCameraDeviceCallbacks(
    val onDisconnected: (CameraDevice) -> Unit,
    val onError: (CameraDevice, Int) -> Unit,
    val onClosed: (CameraDevice) -> Unit,
)

/**
 * Owns the secondary rear-camera stream used to keep the next lens warm.
 *
 * The controller deliberately does not alter PhotonCamera's active capture session.
 * It only opens camera pairs explicitly advertised by CameraManager as concurrent.
 * Readiness is reported after real ImageReader frames have matching Camera2 sensor
 * timestamps; opening a device or configuring a session alone is not sufficient.
 */
class StandbyCameraController(context: Context) {

    companion object {
        private const val TAG = "StandbyCamera"
        private const val MAX_PREVIEW_DIMENSION = 1280
        private const val TARGET_PREVIEW_AREA = 640L * 480L
        private const val MAX_REMEMBERED_TIMESTAMPS = 12
        private const val REQUIRED_MATCHED_FRAMES = 2
        private const val FRAME_READY_TIMEOUT_MS = 5_000L
    }

    private data class StandbyPlan(
        val key: String,
        val activeOpenCameraId: String,
        val standbyOpenCameraId: String,
        val standbyPhysicalCameraId: String?,
        val standbyCameraId: String,
        val lensType: LensType,
        val aspectRatio: Float,
        val previewSize: Size,
        val captureMode: CaptureMode,
        val photoCaptureSize: Size?,
        val previewSurface: Surface?,
    )

    private data class PreviewOutput(
        val size: Size,
        val format: Int,
    )

    private val cameraManager =
        context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val _status = MutableStateFlow(StandbyCameraStatus())
    val status: StateFlow<StandbyCameraStatus> = _status.asStateFlow()

    private var handler: Handler? = null
    private var standbyCameraDevice: CameraDevice? = null
    // Once handed to Camera2Controller, this device is no longer owned by the prewarm path.
    // Its StateCallback still belongs to this controller, so lifecycle events are forwarded.
    private var adoptedCameraDevice: CameraDevice? = null
    private var adoptedDeviceCallbacks: AdoptedCameraDeviceCallbacks? = null
    private var closingCameraDevice: CameraDevice? = null
    private var standbyCaptureSession: CameraCaptureSession? = null
    private var standbyImageReader: ImageReader? = null
    private var alternatePreviewSurface: Surface? = null
    private var alternatePreviewSurfaceTexture: SurfaceTexture? = null
    private var onPreviewSizeChanged: ((Int, Int) -> Unit)? = null
    private var activePlan: StandbyPlan? = null
    private var pendingPlan: StandbyPlan? = null
    private var openRequestGeneration: Long? = null
    private var generation = 0L
    private var frameReadyTimeout: Runnable? = null
    // CameraCaptureSession.close() is asynchronous. Keep resources alive until onClosed().
    private val pendingSessionCloseActions = IdentityHashMap<CameraCaptureSession, () -> Unit>()

    @Volatile
    var isDeviceTransitioning: Boolean = false
        private set

    private val deviceIdleCallbacks = mutableListOf<() -> Unit>()
    private val imageTimestamps = linkedSetOf<Long>()
    private val captureResultTimestamps = linkedSetOf<Long>()
    private var receivedFrameCount = 0L
    private var matchedFrameCount = 0

    /** True only when the already-streaming standby device exactly matches this lens. */
    fun canHandoffTo(targetCamera: CameraInfo): Boolean {
        val plan = activePlan ?: return false
        return _status.value.phase == StandbyCameraPhase.READY &&
            plan.standbyCameraId == targetCamera.cameraId &&
            plan.standbyOpenCameraId == runCatching { targetCamera.getOpenCameraId() }.getOrNull() &&
            standbyCameraDevice != null &&
            standbyCaptureSession != null &&
            matchedFrameCount >= REQUIRED_MATCHED_FRAMES
    }

    /**
     * Detach the ready standby device without closing it. The active controller will
     * configure its normal preview/capture session on this same CameraDevice.
     */
    fun takeOver(
        cameraId: String,
        expectedOpenCameraId: String,
        onReady: (CameraDevice?) -> Unit,
    ) {
        dispatch {
            val plan = activePlan
            val camera = standbyCameraDevice
            val ready = plan != null &&
                plan.standbyCameraId == cameraId &&
                plan.standbyOpenCameraId == expectedOpenCameraId &&
                camera != null &&
                camera.id == expectedOpenCameraId &&
                standbyCaptureSession != null &&
                _status.value.phase == StandbyCameraPhase.READY &&
                matchedFrameCount >= REQUIRED_MATCHED_FRAMES
            if (!ready || camera == null || plan == null) {
                PLog.w(TAG, "Standby handoff unavailable for camera=$cameraId open=$expectedOpenCameraId")
                onReady(null)
                return@dispatch
            }

            val handoffGeneration = generation
            frameReadyTimeout?.let { handler?.removeCallbacks(it) }
            frameReadyTimeout = null
            activePlan = null
            pendingPlan = null
            imageTimestamps.clear()
            captureResultTimestamps.clear()
            receivedFrameCount = 0L
            matchedFrameCount = 0
            _status.value = StandbyCameraStatus(
                phase = StandbyCameraPhase.CONFIGURING,
                activeCameraId = plan.activeOpenCameraId,
                standbyCameraId = cameraId,
                lensType = plan.lensType,
                message = "Waiting for the standby capture session to close before camera handoff",
            )

            // Do not close the ImageReader or configure another session on this device until
            // Camera2 confirms that the old standby session has finished closing.
            closeSessionAndReader {
                if (handoffGeneration != generation || standbyCameraDevice !== camera) {
                    PLog.w(TAG, "Standby camera changed while its capture session was closing")
                    onReady(null)
                    return@closeSessionAndReader
                }

                standbyCameraDevice = null
                adoptedCameraDevice = camera
                adoptedDeviceCallbacks = null
                _status.value = StandbyCameraStatus(
                    phase = StandbyCameraPhase.IDLE,
                    activeCameraId = camera.id,
                    standbyCameraId = cameraId,
                    lensType = plan.lensType,
                    message = "Warm camera handed to active capture pipeline after session close",
                )
                PLog.i(TAG, "Handing off warm CameraDevice ${camera.id} for lens=$cameraId after standby session closed")
                onReady(camera)
            }
        }
    }

    /** Register the main controller's lifecycle handlers after ownership is transferred. */
    fun setAdoptedCameraCallbacks(
        camera: CameraDevice,
        callbacks: AdoptedCameraDeviceCallbacks,
    ) {
        if (adoptedCameraDevice === camera) {
            adoptedDeviceCallbacks = callbacks
        }
    }

    fun prepare(
        activeCamera: CameraInfo?,
        availableCameras: List<CameraInfo>,
        activeOpenCameraId: String,
        previewSize: Size,
        captureMode: CaptureMode = CaptureMode.PHOTO,
        photoCaptureSize: Size? = null,
        callbackHandler: Handler,
        previewSurface: Surface? = null,
        previewSurfaceTexture: SurfaceTexture? = null,
        onPreviewSizeChanged: ((Int, Int) -> Unit)? = null,
    ) {
        handler = callbackHandler
        dispatch {
            alternatePreviewSurface = previewSurface?.takeIf {
                runCatching { it.isValid }.getOrDefault(false)
            }
            alternatePreviewSurfaceTexture = previewSurfaceTexture
            this.onPreviewSizeChanged = onPreviewSizeChanged
            prepareOnCameraThread(
                activeCamera = activeCamera,
                availableCameras = availableCameras,
                activeOpenCameraId = activeOpenCameraId,
                previewSize = previewSize,
                captureMode = captureMode,
                photoCaptureSize = photoCaptureSize,
            )
        }
    }

    fun release(reason: String) {
        dispatch {
            pendingPlan = null
            releaseResources(reason, resetStatus = true)
        }
    }

    /**
     * Camera2Controller calls this before reopening the primary device. If the standby
     * device is still closing (or an open callback is still outstanding), defer the
     * primary open until the standby CameraDevice has delivered onClosed().
     */
    fun whenDeviceIdle(callback: () -> Unit) {
        val callbackHandler = handler
        if (callbackHandler == null) {
            callback()
            return
        }
        val action = {
            if (isDeviceTransitioning) {
                deviceIdleCallbacks += callback
            } else {
                callback()
            }
        }
        if (Looper.myLooper() == callbackHandler.looper) {
            action()
        } else {
            callbackHandler.post { action() }
        }
    }

    private fun dispatch(action: () -> Unit) {
        val callbackHandler = handler ?: return
        if (Looper.myLooper() == callbackHandler.looper) {
            action()
        } else {
            callbackHandler.post { action() }
        }
    }

    private fun prepareOnCameraThread(
        activeCamera: CameraInfo?,
        availableCameras: List<CameraInfo>,
        activeOpenCameraId: String,
        previewSize: Size,
        captureMode: CaptureMode,
        photoCaptureSize: Size?,
    ) {
        val plan = resolvePlan(
            activeCamera = activeCamera,
            availableCameras = availableCameras,
            activeOpenCameraId = activeOpenCameraId,
            previewSize = previewSize,
            captureMode = captureMode,
            photoCaptureSize = photoCaptureSize,
        ) ?: run {
            pendingPlan = null
            releaseResources("standby plan unavailable", resetStatus = false)
            return
        }

        if (activePlan?.key == plan.key &&
            standbyCameraDevice != null &&
            standbyCaptureSession != null
        ) {
            return
        }
        if (pendingPlan?.key == plan.key) return

        pendingPlan = plan
        releaseResources("standby target changed", resetStatus = false)
        openPendingPlanWhenIdle()
    }

    private fun resolvePlan(
        activeCamera: CameraInfo?,
        availableCameras: List<CameraInfo>,
        activeOpenCameraId: String,
        previewSize: Size,
        captureMode: CaptureMode,
        photoCaptureSize: Size?,
    ): StandbyPlan? {
        if (activeCamera == null || activeOpenCameraId.isBlank()) {
            publishUnavailable(StandbyCameraPhase.NO_TARGET_LENS, "Active camera information unavailable")
            return null
        }
        if (activeCamera.lensFacing != CameraCharacteristics.LENS_FACING_BACK) {
            publishUnavailable(StandbyCameraPhase.NO_TARGET_LENS, "Standby stream is only used for rear lenses")
            return null
        }

        val rearCameras = availableCameras.filter {
            it.lensFacing == CameraCharacteristics.LENS_FACING_BACK
        }
        val targetCamera = if (activeCamera.lensType == LensType.BACK_ULTRA_WIDE) {
            rearCameras.firstOrNull {
                it.lensType == LensType.BACK_MAIN && it.cameraId != activeCamera.cameraId
            }
        } else {
            rearCameras.firstOrNull {
                it.lensType == LensType.BACK_ULTRA_WIDE && it.cameraId != activeCamera.cameraId
            } ?: rearCameras.firstOrNull {
                it.lensType == LensType.BACK_MAIN && it.cameraId != activeCamera.cameraId
            }
        }

        if (targetCamera == null) {
            publishUnavailable(StandbyCameraPhase.NO_TARGET_LENS, "No alternate rear lens was discovered")
            return null
        }

        val standbyOpenCameraId = runCatching { targetCamera.getOpenCameraId() }.getOrNull()
        if (standbyOpenCameraId.isNullOrBlank()) {
            publishUnavailable(StandbyCameraPhase.FAILED, "Target lens has no valid Camera2 open ID")
            return null
        }

        val activePhysicalId = resolvePhysicalCameraId(activeCamera)
        val standbyPhysicalId = resolvePhysicalCameraId(targetCamera)
        if (activePhysicalId != null && activePhysicalId == standbyPhysicalId) {
            publishUnavailable(StandbyCameraPhase.NO_TARGET_LENS, "Active and standby entries resolve to the same physical sensor")
            return null
        }

        if (activeOpenCameraId == standbyOpenCameraId) {
            publishUnavailable(
                phase = StandbyCameraPhase.LOGICAL_CAMERA_SHARED_SESSION_REQUIRED,
                message = "Both lenses belong to one logical CameraDevice; they must be added to its shared session",
                activeCameraId = activeOpenCameraId,
                standbyCameraId = targetCamera.cameraId,
                lensType = targetCamera.lensType,
            )
            return null
        }

        // Do not require CameraManager.concurrentCameraIds to advertise the pair here.
        // Some HALs (including devices that support the same persistent dual-session pattern
        // as VCamera) permit both rear CameraDevices to stay open even when the concurrent-pair
        // metadata is incomplete. Try opening the standby device and let Camera2's real open /
        // session callbacks decide. Any rejection is isolated to this standby plan; the active
        // camera remains untouched and the normal single-camera switch path remains available.
        val advertisedConcurrentPair = try {
            cameraManager.concurrentCameraIds.any { ids ->
                ids.contains(activeOpenCameraId) && ids.contains(standbyOpenCameraId)
            }
        } catch (error: Exception) {
            PLog.w(TAG, "Unable to read concurrent camera combinations; trying guarded standby open", error)
            false
        }
        if (!advertisedConcurrentPair) {
            PLog.i(
                TAG,
                "Camera pair is not advertised as concurrent; attempting guarded persistent standby open: " +
                    "active=$activeOpenCameraId, standby=$standbyOpenCameraId",
            )
        }

        val ratio = if (previewSize.width > 0 && previewSize.height > 0) {
            previewSize.width.toFloat() / previewSize.height.toFloat()
        } else {
            4f / 3f
        }
        val livePreviewSurface = resolveStandbyPreviewSurface(
            cameraId = standbyPhysicalId ?: standbyOpenCameraId,
            requestedSize = previewSize,
        )
        val livePreviewSizeKey = if (livePreviewSurface != null) {
            "${previewSize.width}x${previewSize.height}"
        } else {
            "reader-only"
        }
        return StandbyPlan(
            key = listOf(
                activeOpenCameraId,
                standbyOpenCameraId,
                standbyPhysicalId.orEmpty(),
                targetCamera.cameraId,
                captureMode.name,
                photoCaptureSize?.let { "${it.width}x${it.height}" }.orEmpty(),
                livePreviewSurface?.let { System.identityHashCode(it) } ?: 0,
                livePreviewSizeKey,
            ).joinToString("|"),
            activeOpenCameraId = activeOpenCameraId,
            standbyOpenCameraId = standbyOpenCameraId,
            standbyPhysicalCameraId = standbyPhysicalId,
            standbyCameraId = targetCamera.cameraId,
            lensType = targetCamera.lensType,
            aspectRatio = ratio,
            previewSize = previewSize,
            captureMode = captureMode,
            photoCaptureSize = photoCaptureSize?.takeIf { it.width > 0 && it.height > 0 },
            previewSurface = livePreviewSurface,
        )
    }

    private fun resolveStandbyPreviewSurface(
        cameraId: String,
        requestedSize: Size,
    ): Surface? {
        val surface = alternatePreviewSurface?.takeIf {
            runCatching { it.isValid }.getOrDefault(false)
        } ?: return null
        val texture = alternatePreviewSurfaceTexture ?: return null
        val characteristics = runCatching {
            cameraManager.getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return null
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.toList()
            .orEmpty()
        if (sizes.isEmpty()) return null

        val requestedAspect = requestedSize.width.toFloat() /
            requestedSize.height.coerceAtLeast(1).toFloat()
        val requestedArea = requestedSize.width.toLong() * requestedSize.height.toLong()
        val selected = sizes.minWithOrNull(
            compareBy<Size>(
                { abs(it.width.toFloat() / it.height.toFloat() - requestedAspect) },
                { abs(it.width.toLong() * it.height.toLong() - requestedArea) },
            )
        ) ?: return null

        return try {
            texture.setDefaultBufferSize(selected.width, selected.height)
            onPreviewSizeChanged?.invoke(selected.width, selected.height)
            PLog.i(
                TAG,
                "Standby GL preview output selected: camera=$cameraId, size=${selected.width}x${selected.height}",
            )
            surface
        } catch (error: Exception) {
            PLog.w(TAG, "Could not configure alternate GL preview SurfaceTexture", error)
            null
        }
    }

    private fun resolvePhysicalCameraId(camera: CameraInfo): String? {
        camera.outputPhysicalCameraId?.let { return it }
        val openCameraId = runCatching { camera.getOpenCameraId() }.getOrNull() ?: return null
        if (camera.logicalCameraId == null) return null

        return camera.cameraId.takeIf {
            it != openCameraId && camera.physicalCameraIds.contains(it)
        } ?: camera.getBoundPhysicalCameraId(1f)?.takeIf { it != openCameraId }
    }

    private fun openPendingPlanWhenIdle() {
        val callbackHandler = handler ?: return
        if (isDeviceTransitioning) {
            whenDeviceIdle {
                openPendingPlanWhenIdle()
            }
            return
        }
        val plan = pendingPlan ?: return
        pendingPlan = null
        openPlan(plan, callbackHandler)
    }

    @Suppress("MissingPermission")
    private fun openPlan(plan: StandbyPlan, callbackHandler: Handler) {
        // A logical multi-camera's public stream map can describe the wide/main sensor rather
        // than the physical lens bound to this standby output. Resolve stream sizes against the
        // exact physical camera whenever the target is routed through a logical device.
        val outputCameraId = plan.standbyPhysicalCameraId ?: plan.standbyOpenCameraId
        val output = resolvePreviewOutput(
            cameraId = outputCameraId,
            desiredAspectRatio = plan.aspectRatio,
            requestedSize = plan.photoCaptureSize.takeIf { plan.captureMode == CaptureMode.PHOTO },
        )
        if (output == null) {
            publishUnavailable(
                phase = StandbyCameraPhase.FAILED,
                message = "Target camera has no usable YUV or PRIVATE preview output size",
                activeCameraId = plan.activeOpenCameraId,
                standbyCameraId = plan.standbyCameraId,
                lensType = plan.lensType,
            )
            return
        }

        val reader = try {
            ImageReader.newInstance(output.size.width, output.size.height, output.format, 3)
        } catch (error: Exception) {
            publishUnavailable(
                phase = StandbyCameraPhase.FAILED,
                message = "Could not allocate standby ImageReader: " + (error.message ?: error.javaClass.simpleName),
                activeCameraId = plan.activeOpenCameraId,
                standbyCameraId = plan.standbyCameraId,
                lensType = plan.lensType,
            )
            return
        }

        val openGeneration = ++generation
        activePlan = plan
        standbyImageReader = reader
        imageTimestamps.clear()
        captureResultTimestamps.clear()
        receivedFrameCount = 0L
        matchedFrameCount = 0
        _status.value = StandbyCameraStatus(
            phase = StandbyCameraPhase.OPENING,
            activeCameraId = plan.activeOpenCameraId,
            standbyCameraId = plan.standbyCameraId,
            lensType = plan.lensType,
            message = "Opening real standby camera stream",
        )

        reader.setOnImageAvailableListener({ source ->
            if (openGeneration != generation || source !== standbyImageReader) {
                runCatching { source.acquireLatestImage()?.close() }
                return@setOnImageAvailableListener
            }
            val image = try {
                source.acquireLatestImage()
            } catch (error: IllegalStateException) {
                PLog.w(TAG, "Standby frame acquisition failed", error)
                null
            } ?: return@setOnImageAvailableListener

            try {
                val timestamp = image.timestamp
                if (timestamp > 0L) {
                    receivedFrameCount += 1L
                    rememberTimestamp(imageTimestamps, timestamp)
                    updateReadinessFromMatchedFrames(openGeneration)
                }
            } finally {
                image.close()
            }
        }, callbackHandler)

        isDeviceTransitioning = true
        openRequestGeneration = openGeneration
        try {
            cameraManager.openCamera(
                plan.standbyOpenCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (openGeneration != generation || activePlan?.key != plan.key) {
                            PLog.w(TAG, "Discarding stale standby camera open callback for " + camera.id)
                            openRequestGeneration = null
                            closeOpenedCamera(camera)
                            return
                        }
                        openRequestGeneration = null
                        standbyCameraDevice = camera
                        setDeviceTransitioning(false)
                        configureStandbySession(camera, reader, plan, openGeneration)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        if (camera === adoptedCameraDevice) {
                            val callbacks = adoptedDeviceCallbacks
                            if (callbacks != null) callbacks.onDisconnected(camera)
                            else closeOpenedCamera(camera)
                            return
                        }
                        if (openRequestGeneration == openGeneration) {
                            openRequestGeneration = null
                        }
                        if (openGeneration == generation) {
                            standbyCameraDevice = camera
                            failCurrentPlan("Standby camera disconnected", null)
                        } else {
                            closeOpenedCamera(camera)
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        if (camera === adoptedCameraDevice) {
                            val callbacks = adoptedDeviceCallbacks
                            if (callbacks != null) callbacks.onError(camera, error)
                            else closeOpenedCamera(camera)
                            return
                        }
                        if (openRequestGeneration == openGeneration) {
                            openRequestGeneration = null
                        }
                        if (openGeneration == generation) {
                            standbyCameraDevice = camera
                            failCurrentPlan("Standby camera open error: " + error, null)
                        } else {
                            closeOpenedCamera(camera)
                        }
                    }

                    override fun onClosed(camera: CameraDevice) {
                        if (camera === adoptedCameraDevice) {
                            val callbacks = adoptedDeviceCallbacks
                            adoptedCameraDevice = null
                            adoptedDeviceCallbacks = null
                            if (callbacks != null) {
                                callbacks.onClosed(camera)
                                return
                            }
                            // A handoff can fail before the active controller installs its
                            // callbacks. Fall through so a forced close still releases the
                            // standby transition latch instead of leaving opens blocked.
                        }
                        if (closingCameraDevice === camera) {
                            closingCameraDevice = null
                        }
                        if (standbyCameraDevice === camera) {
                            standbyCameraDevice = null
                        }
                        if (openRequestGeneration == null && closingCameraDevice == null) {
                            setDeviceTransitioning(false)
                        }
                    }
                },
                callbackHandler,
            )
        } catch (error: CameraAccessException) {
            openRequestGeneration = null
            setDeviceTransitioning(false)
            failCurrentPlan("Standby camera could not be opened: " + error.message, error)
        } catch (error: SecurityException) {
            openRequestGeneration = null
            setDeviceTransitioning(false)
            failCurrentPlan("Standby camera permission was revoked", error)
        } catch (error: RuntimeException) {
            openRequestGeneration = null
            setDeviceTransitioning(false)
            failCurrentPlan("Standby camera open request failed: " + error.message, error)
        }
    }

    private fun resolvePreviewOutput(
        cameraId: String,
        desiredAspectRatio: Float,
        requestedSize: Size? = null,
    ): PreviewOutput? {
        val characteristics = try {
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (error: Exception) {
            PLog.w(TAG, "Could not read standby camera characteristics for " + cameraId, error)
            return null
        }
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null

        val candidateFormats = listOf(ImageFormat.YUV_420_888, ImageFormat.PRIVATE)
        for (format in candidateFormats) {
            val sizes = streamMap.getOutputSizes(format)?.toList().orEmpty()
            if (sizes.isEmpty()) continue
            // In photo mode the standby stream must exercise the same sensor-resolution tier
            // that will be used after handoff. In video mode this reader is only a lightweight
            // timestamp source; the alternate SurfaceTexture carries the selected video input
            // resolution, so keep this reader bounded to avoid unnecessary bandwidth.
            val candidates = if (requestedSize != null) {
                sizes
            } else {
                sizes.filter {
                    maxOf(it.width, it.height) <= MAX_PREVIEW_DIMENSION
                }.ifEmpty { sizes }
            }
            val requestedArea = requestedSize?.let { it.width.toLong() * it.height.toLong() }
            val selectedSize = candidates.minWithOrNull(
                compareBy<Size>(
                    { abs(it.width.toFloat() / it.height.toFloat() - desiredAspectRatio) },
                    {
                        if (requestedArea != null) {
                            abs(it.width.toLong() * it.height.toLong() - requestedArea)
                        } else {
                            abs(it.width.toLong() * it.height.toLong() - TARGET_PREVIEW_AREA)
                        }
                    },
                ),
            ) ?: continue
            return PreviewOutput(selectedSize, format)
        }
        return null
    }

    private fun configureStandbySession(
        camera: CameraDevice,
        reader: ImageReader,
        plan: StandbyPlan,
        openGeneration: Long,
        includeAlternatePreviewSurface: Boolean = true,
    ) {
        if (openGeneration != generation || camera !== standbyCameraDevice) {
            closeOpenedCamera(camera)
            return
        }
        val callbackHandler = handler ?: run {
            failCurrentPlan("Camera handler stopped during standby configuration", null)
            return
        }

        _status.value = _status.value.copy(
            phase = StandbyCameraPhase.CONFIGURING,
            message = "Configuring secondary preview capture session",
        )

        val livePreviewSurface = plan.previewSurface.takeIf { includeAlternatePreviewSurface }
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                livePreviewSurface?.let(::addTarget)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                val afModes = cameraManager.getCameraCharacteristics(camera.id)
                    .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val preferredAfMode = when {
                    afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                        CaptureRequest.CONTROL_AF_MODE_AUTO
                    else -> CaptureRequest.CONTROL_AF_MODE_OFF
                }
                set(CaptureRequest.CONTROL_AF_MODE, preferredAfMode)
            }

            val outputConfiguration = OutputConfiguration(reader.surface).apply {
                plan.standbyPhysicalCameraId?.let { physicalId ->
                    setPhysicalCameraId(physicalId)
                }
            }
            val outputConfigurations = buildList {
                add(outputConfiguration)
                livePreviewSurface?.let { liveSurface ->
                    add(OutputConfiguration(liveSurface).apply {
                        plan.standbyPhysicalCameraId?.let { physicalId -> setPhysicalCameraId(physicalId) }
                    })
                }
            }
            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigurations,
                Executor { command -> callbackHandler.post(command) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (openGeneration != generation ||
                            camera !== standbyCameraDevice ||
                            reader !== standbyImageReader
                        ) {
                            runCatching { session.close() }
                            return
                        }
                        standbyCaptureSession = session
                        _status.value = _status.value.copy(
                            phase = StandbyCameraPhase.WAITING_FOR_FRAMES,
                            message = if (livePreviewSurface != null) {
                                "Standby camera and live GL preview are streaming"
                            } else {
                                "Standby session active; waiting for matching camera frames"
                            },
                        )
                        try {
                            session.setRepeatingRequest(
                                builder.build(),
                                object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult,
                                    ) {
                                        if (openGeneration != generation ||
                                            session !== standbyCaptureSession
                                        ) {
                                            return
                                        }
                                        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                                        rememberTimestamp(captureResultTimestamps, timestamp)
                                        updateReadinessFromMatchedFrames(openGeneration)
                                    }

                                    override fun onCaptureFailed(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        failure: android.hardware.camera2.CaptureFailure,
                                    ) {
                                        if (openGeneration == generation &&
                                            session === standbyCaptureSession
                                        ) {
                                            PLog.w(TAG, "Standby capture failed: reason=" + failure.reason)
                                        }
                                    }
                                },
                                callbackHandler,
                            )
                            scheduleFrameReadyTimeout(openGeneration, callbackHandler)
                        } catch (error: Exception) {
                            if (livePreviewSurface != null) {
                                PLog.w(
                                    TAG,
                                    "Standby GL output rejected while starting repeating request; retrying without it: ${error.message}",
                                )
                                pendingSessionCloseActions[session] = {
                                    if (
                                        openGeneration == generation &&
                                        camera === standbyCameraDevice &&
                                        reader === standbyImageReader
                                    ) {
                                        configureStandbySession(
                                            camera,
                                            reader,
                                            plan,
                                            openGeneration,
                                            includeAlternatePreviewSurface = false,
                                        )
                                    }
                                }
                                try {
                                    // A failed session is still owned by Camera2 until onClosed.
                                    session.close()
                                } catch (closeError: Exception) {
                                    pendingSessionCloseActions.remove(session)
                                    failCurrentPlan(
                                        "Could not close rejected standby session before retry: ${closeError.message}",
                                        closeError,
                                    )
                                }
                            } else {
                                failCurrentPlan("Could not start standby repeating request: " + error.message, error)
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (openGeneration == generation && livePreviewSurface != null) {
                            PLog.w(TAG, "Standby GL output combination rejected; retrying reader-only standby")
                            pendingSessionCloseActions[session] = {
                                if (
                                    openGeneration == generation &&
                                    camera === standbyCameraDevice &&
                                    reader === standbyImageReader
                                ) {
                                    configureStandbySession(
                                        camera,
                                        reader,
                                        plan,
                                        openGeneration,
                                        includeAlternatePreviewSurface = false,
                                    )
                                }
                            }
                            try {
                                // Do not race a replacement session against this failed session's
                                // asynchronous teardown on the same CameraDevice.
                                session.close()
                            } catch (closeError: Exception) {
                                pendingSessionCloseActions.remove(session)
                                failCurrentPlan(
                                    "Could not close failed standby session before retry: ${closeError.message}",
                                    closeError,
                                )
                            }
                        } else {
                            runCatching { session.close() }
                            if (openGeneration == generation) {
                                failCurrentPlan("Standby capture session configuration failed", null)
                            }
                        }
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        if (standbyCaptureSession === session) {
                            standbyCaptureSession = null
                        }
                        pendingSessionCloseActions.remove(session)?.invoke()
                    }
                },
            )
            camera.createCaptureSession(sessionConfiguration)
        } catch (error: Exception) {
            if (livePreviewSurface != null) {
                PLog.w(
                    TAG,
                    "Standby GL output could not be configured; retrying reader-only standby: ${error.message}",
                )
                configureStandbySession(
                    camera,
                    reader,
                    plan,
                    openGeneration,
                    includeAlternatePreviewSurface = false,
                )
            } else {
                failCurrentPlan("Standby capture session could not be created: " + error.message, error)
            }
        }
    }

    private fun scheduleFrameReadyTimeout(openGeneration: Long, callbackHandler: Handler) {
        frameReadyTimeout?.let(callbackHandler::removeCallbacks)
        val timeout = Runnable {
            if (openGeneration != generation ||
                _status.value.phase == StandbyCameraPhase.READY
            ) {
                return@Runnable
            }
            failCurrentPlan(
                "Standby stream did not deliver enough timestamp-matched frames within " +
                    FRAME_READY_TIMEOUT_MS + "ms",
                null,
            )
        }
        frameReadyTimeout = timeout
        callbackHandler.postDelayed(timeout, FRAME_READY_TIMEOUT_MS)
    }

    private fun updateReadinessFromMatchedFrames(openGeneration: Long) {
        if (openGeneration != generation) return
        matchedFrameCount = imageTimestamps.count { captureResultTimestamps.contains(it) }
        val current = _status.value
        if (current.phase != StandbyCameraPhase.WAITING_FOR_FRAMES &&
            current.phase != StandbyCameraPhase.READY
        ) {
            return
        }
        if (matchedFrameCount >= REQUIRED_MATCHED_FRAMES) {
            frameReadyTimeout?.let { handler?.removeCallbacks(it) }
            frameReadyTimeout = null
            _status.value = current.copy(
                phase = StandbyCameraPhase.READY,
                frameCount = receivedFrameCount,
                matchedFrameCount = matchedFrameCount,
                message = "Standby camera is streaming real preview frames",
            )
        } else {
            _status.value = current.copy(
                frameCount = receivedFrameCount,
                matchedFrameCount = matchedFrameCount,
            )
        }
    }

    private fun rememberTimestamp(target: LinkedHashSet<Long>, timestamp: Long) {
        target.add(timestamp)
        while (target.size > MAX_REMEMBERED_TIMESTAMPS) {
            target.remove(target.first())
        }
    }

    private fun failCurrentPlan(message: String, error: Throwable?) {
        val plan = activePlan
        if (error != null) {
            PLog.w(TAG, message, error)
        } else {
            PLog.w(TAG, message)
        }
        frameReadyTimeout?.let { handler?.removeCallbacks(it) }
        frameReadyTimeout = null
        _status.value = StandbyCameraStatus(
            phase = StandbyCameraPhase.FAILED,
            activeCameraId = plan?.activeOpenCameraId,
            standbyCameraId = plan?.standbyCameraId,
            lensType = plan?.lensType,
            frameCount = receivedFrameCount,
            matchedFrameCount = matchedFrameCount,
            message = message,
        )
        activePlan = null
        closeSessionAndReader()
        standbyCameraDevice?.let(::closeOpenedCamera)
    }

    private fun releaseResources(reason: String, resetStatus: Boolean) {
        generation += 1L
        activePlan = null
        frameReadyTimeout?.let { handler?.removeCallbacks(it) }
        frameReadyTimeout = null
        imageTimestamps.clear()
        captureResultTimestamps.clear()
        receivedFrameCount = 0L
        matchedFrameCount = 0

        closeSessionAndReader()
        val device = standbyCameraDevice
        if (device != null) {
            PLog.d(TAG, "Releasing standby camera " + device.id + ": " + reason)
            closeOpenedCamera(device)
        } else if (openRequestGeneration == null && closingCameraDevice == null) {
            setDeviceTransitioning(false)
        }

        if (resetStatus) {
            _status.value = StandbyCameraStatus()
        }
    }

    private fun closeSessionAndReader(afterSessionClosed: (() -> Unit)? = null) {
        val session = standbyCaptureSession
        standbyCaptureSession = null
        val reader = standbyImageReader
        standbyImageReader = null

        val finishClose = {
            if (reader != null) {
                runCatching { reader.setOnImageAvailableListener(null, null) }
                runCatching { reader.close() }
            }
            afterSessionClosed?.invoke()
            Unit
        }

        if (session == null) {
            finishClose()
            return
        }

        // Register before close(): onClosed() is dispatched asynchronously on the camera handler.
        pendingSessionCloseActions[session] = finishClose
        try {
            // Closing this preview-only session stops its repeating request. Avoid an
            // unnecessary abortCaptures() pipeline flush during the lens handoff.
            session.close()
        } catch (error: Exception) {
            PLog.w(TAG, "Failed to close standby capture session; closing its device before releasing outputs", error)
            val device = standbyCameraDevice
            if (device != null) {
                closeOpenedCamera(device)
                whenDeviceIdle {
                    pendingSessionCloseActions.remove(session)?.invoke()
                }
            } else {
                pendingSessionCloseActions.remove(session)?.invoke()
            }
        }
    }

    private fun closeOpenedCamera(camera: CameraDevice) {
        if (standbyCameraDevice === camera) {
            standbyCameraDevice = null
        }
        closingCameraDevice = camera
        isDeviceTransitioning = true
        try {
            camera.close()
        } catch (error: Exception) {
            PLog.w(TAG, "Ignoring standby camera close error", error)
            if (closingCameraDevice === camera) {
                closingCameraDevice = null
            }
            if (openRequestGeneration == null) {
                setDeviceTransitioning(false)
            }
        }
    }

    private fun setDeviceTransitioning(transitioning: Boolean) {
        isDeviceTransitioning = transitioning
        if (!transitioning) {
            val callbacks = deviceIdleCallbacks.toList()
            deviceIdleCallbacks.clear()
            callbacks.forEach { callback ->
                runCatching { callback() }
            }
        }
    }

    private fun publishUnavailable(
        phase: StandbyCameraPhase,
        message: String,
        activeCameraId: String? = null,
        standbyCameraId: String? = null,
        lensType: LensType? = null,
    ) {
        _status.value = StandbyCameraStatus(
            phase = phase,
            activeCameraId = activeCameraId,
            standbyCameraId = standbyCameraId,
            lensType = lensType,
            message = message,
        )
    }
}

enum class StandbyCameraPhase {
    IDLE,
    NO_TARGET_LENS,
    LOGICAL_CAMERA_SHARED_SESSION_REQUIRED,
    CONCURRENT_COMBINATION_UNSUPPORTED,
    OPENING,
    CONFIGURING,
    WAITING_FOR_FRAMES,
    READY,
    FAILED,
}

data class StandbyCameraStatus(
    val phase: StandbyCameraPhase = StandbyCameraPhase.IDLE,
    val activeCameraId: String? = null,
    val standbyCameraId: String? = null,
    val lensType: LensType? = null,
    val frameCount: Long = 0L,
    val matchedFrameCount: Int = 0,
    val message: String? = null,
)
