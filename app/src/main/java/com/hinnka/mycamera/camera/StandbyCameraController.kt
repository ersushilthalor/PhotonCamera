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
        private const val MAX_AUTOMATIC_RETRIES = 3
        private const val MAX_CAMERA_LIMIT_FAILURES = 3
        private val RETRY_BACKOFF_MS = longArrayOf(500L, 1_500L, 3_500L)
    }

    private data class RetryState(
        var attempts: Int = 0,
        var maxCameraLimitFailures: Int = 0,
        var permanentlyUnsupportedReason: String? = null,
    )

    private data class StandbyPlan(
        val key: String,
        val activeOpenCameraId: String,
        val standbyOpenCameraId: String,
        val standbyPhysicalCameraId: String?,
        val standbyCameraId: String,
        val lensType: LensType,
        val aspectRatio: Float,
        val previewSize: Size,
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
    // CameraDevices opened by this controller keep their original StateCallback for life.
    // Keep identity-keyed routing so ownership can move active -> standby -> active repeatedly.
    private val adoptedDeviceCallbacks = IdentityHashMap<CameraDevice, AdoptedCameraDeviceCallbacks?>()
    private val standbyOpenedDevices = java.util.Collections.newSetFromMap(
        IdentityHashMap<CameraDevice, Boolean>()
    )
    // Devices originally opened by Camera2Controller have callbacks there; route those events
    // back here only while the device is owned by the standby pipeline.
    private val adoptedStandbyDevices = java.util.Collections.newSetFromMap(
        IdentityHashMap<CameraDevice, Boolean>()
    )
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
    private val retryStates = mutableMapOf<String, RetryState>()
    private var retryRunnable: Runnable? = null
    private var retryScheduledPlanKey: String? = null

    @Volatile
    var isDeviceTransitioning: Boolean = false
        private set

    private val deviceIdleCallbacks = mutableListOf<() -> Unit>()
    private val imageTimestamps = linkedSetOf<Long>()
    private val captureResultTimestamps = linkedSetOf<Long>()
    private var receivedFrameCount = 0L
    private var matchedFrameCount = 0
    private var lastLoggedTimestampMismatch: Pair<Long, Long>? = null

    /** Returns the exact readiness blockers without changing standby state. */
    fun handoffBlockers(targetCamera: CameraInfo): List<String> {
        val plan = activePlan
        val targetOpenCameraId = runCatching { targetCamera.getOpenCameraId() }.getOrNull()
        return buildList {
            if (_status.value.phase != StandbyCameraPhase.READY) {
                add("standby_phase=${_status.value.phase}")
            }
            if (plan == null) {
                add("standby_plan_missing")
            } else {
                if (plan.standbyCameraId != targetCamera.cameraId) {
                    add("standby_camera_id_mismatch(expected=${plan.standbyCameraId},actual=${targetCamera.cameraId})")
                }
                if (plan.standbyOpenCameraId != targetOpenCameraId) {
                    add("standby_open_camera_id_mismatch(expected=${plan.standbyOpenCameraId},actual=$targetOpenCameraId)")
                }
            }
            if (standbyCameraDevice == null) add("standby_device_missing")
            if (standbyCaptureSession == null) add("standby_session_missing")
            if (matchedFrameCount < REQUIRED_MATCHED_FRAMES) {
                add("matched_frames_insufficient(actual=$matchedFrameCount,required=$REQUIRED_MATCHED_FRAMES)")
            }
        }
    }

    /** True only when the already-streaming standby device exactly matches this lens. */
    fun canHandoffTo(targetCamera: CameraInfo): Boolean =
        handoffBlockers(targetCamera).isEmpty()

    /**
     * Log the active/target camera topology for each user lens-switch request.
     * This is diagnostic-only and deliberately does not influence the standby plan.
     */
    fun logCameraPairForLensSwitch(activeCamera: CameraInfo?, targetCamera: CameraInfo?) {
        val activeOpenCameraId = activeCamera?.let {
            runCatching { it.getOpenCameraId() }.getOrNull()
        }
        val targetOpenCameraId = targetCamera?.let {
            runCatching { it.getOpenCameraId() }.getOrNull()
        }
        val concurrentIdsResult = runCatching { cameraManager.concurrentCameraIds }
        val pairAdvertised = if (
            activeOpenCameraId != null &&
            targetOpenCameraId != null &&
            activeOpenCameraId != targetOpenCameraId
        ) {
            concurrentIdsResult.getOrNull()?.any { ids ->
                ids.contains(activeOpenCameraId) && ids.contains(targetOpenCameraId)
            }
        } else if (activeOpenCameraId != null && activeOpenCameraId == targetOpenCameraId) {
            false
        } else {
            null
        }
        val concurrentPairs = concurrentIdsResult.getOrNull()
            ?.map { ids -> ids.sorted().joinToString("+") }
            ?.sorted()
            ?.joinToString(";")
            ?: concurrentIdsResult.exceptionOrNull()?.let {
                "unavailable:${it.javaClass.simpleName}:${it.message}"
            }
            ?: "unavailable"

        InstantLensDiagnostics.log(
            "CAMERA_PAIR",
            "activeCameraId=${activeCamera?.cameraId ?: "null"} " +
                "targetCameraId=${targetCamera?.cameraId ?: "null"} " +
                "activeOpenCameraId=${activeOpenCameraId ?: "null"} " +
                "targetOpenCameraId=${targetOpenCameraId ?: "null"} " +
                "logicalSharedDevice=${activeOpenCameraId != null && activeOpenCameraId == targetOpenCameraId} " +
                "pairInConcurrentCameraIds=${pairAdvertised?.toString() ?: "unknown"} " +
                "concurrentPairs=[$concurrentPairs]",
        )
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
        InstantLensDiagnostics.log(
            "TAKE_OVER",
            "result=request cameraId=$cameraId expectedOpenCameraId=$expectedOpenCameraId",
        )
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
            if (!ready || camera == null) {
                InstantLensDiagnostics.log(
                    "TAKE_OVER",
                    "result=failed cameraId=$cameraId expectedOpenCameraId=$expectedOpenCameraId " +
                        "reason=standby_not_ready phase=${_status.value.phase} " +
                        "device=${camera?.id ?: "null"} sessionReady=${standbyCaptureSession != null} " +
                        "matchedFrames=$matchedFrameCount",
                )
                PLog.w(TAG, "Standby handoff unavailable for camera=$cameraId open=$expectedOpenCameraId")
                onReady(null)
                return@dispatch
            }

            val matchedFramesAtHandoff = matchedFrameCount
            frameReadyTimeout?.let { handler?.removeCallbacks(it) }
            frameReadyTimeout = null
            closeSessionAndReader()
            standbyCameraDevice = null
            if (standbyOpenedDevices.remove(camera)) {
                adoptedDeviceCallbacks[camera] = null
            }
            adoptedStandbyDevices.remove(camera)
            activePlan = null
            pendingPlan = null
            imageTimestamps.clear()
            captureResultTimestamps.clear()
            receivedFrameCount = 0L
            matchedFrameCount = 0
            publishStatus(
                StandbyCameraStatus(
                    phase = StandbyCameraPhase.IDLE,
                    activeCameraId = camera.id,
                    standbyCameraId = cameraId,
                    lensType = plan.lensType,
                    message = "Warm camera handed to active capture pipeline",
                )
            )
            InstantLensDiagnostics.log(
                "TAKE_OVER",
                "result=success cameraId=$cameraId deviceId=${camera.id} matchedFrames=$matchedFramesAtHandoff",
            )
            PLog.i(TAG, "Handing off warm CameraDevice ${camera.id} for lens=$cameraId")
            onReady(camera)
        }
    }

    /** Register the main controller's lifecycle handlers after ownership is transferred. */
    fun setAdoptedCameraCallbacks(
        camera: CameraDevice,
        callbacks: AdoptedCameraDeviceCallbacks,
    ) {
        if (adoptedDeviceCallbacks.containsKey(camera)) {
            adoptedDeviceCallbacks[camera] = callbacks
        }
    }

    /**
     * Adopt the previous active CameraDevice as the next standby device without reopening it.
     * Its old active session has already been retired; the new standby session feeds the
     * alternate GL surface so the next lens switch can hand the same device straight back.
     */
    fun adoptCurrentCameraDevice(
        camera: CameraDevice,
        activeCamera: CameraInfo?,
        availableCameras: List<CameraInfo>,
        activeOpenCameraId: String,
        previewSize: Size,
        callbackHandler: Handler,
        previewSurface: Surface? = null,
        previewSurfaceTexture: SurfaceTexture? = null,
        onPreviewSizeChanged: ((Int, Int) -> Unit)? = null,
        onAdoptionFailed: (CameraDevice, String) -> Unit,
    ) {
        handler = callbackHandler
        dispatch {
            alternatePreviewSurface = previewSurface?.takeIf {
                runCatching { it.isValid }.getOrDefault(false)
            }
            alternatePreviewSurfaceTexture = previewSurfaceTexture
            this.onPreviewSizeChanged = onPreviewSizeChanged

            val plan = resolvePlan(activeCamera, availableCameras, activeOpenCameraId, previewSize)
            if (plan == null || plan.standbyOpenCameraId != camera.id) {
                val reason = if (plan == null) {
                    "No valid reverse standby plan after handoff"
                } else {
                    "Retained device mismatch: expected=${plan.standbyOpenCameraId}, actual=${camera.id}"
                }
                InstantLensDiagnostics.log(
                    "STANDBY_ADOPT",
                    "result=failed deviceId=${camera.id} reason=$reason",
                )
                onAdoptionFailed(camera, reason)
                return@dispatch
            }

            val output = resolvePreviewOutput(plan.standbyOpenCameraId, plan.aspectRatio)
            if (output == null) {
                val reason = "Retained standby camera has no usable YUV or PRIVATE preview output size"
                markPlanUnsupported(plan, reason)
                onAdoptionFailed(camera, reason)
                return@dispatch
            }
            val reader = try {
                ImageReader.newInstance(output.size.width, output.size.height, output.format, 3)
            } catch (error: Exception) {
                val reason = "Could not allocate adopted standby ImageReader: " +
                    (error.message ?: error.javaClass.simpleName)
                InstantLensDiagnostics.log(
                    "STANDBY_ADOPT",
                    "result=failed deviceId=${camera.id} reason=$reason",
                )
                onAdoptionFailed(camera, reason)
                return@dispatch
            }

            cancelScheduledRetry("adopting retained active device")
            generation += 1L
            val openGeneration = generation
            activePlan = plan
            pendingPlan = null
            standbyCameraDevice = camera
            adoptedStandbyDevices += camera
            if (adoptedDeviceCallbacks.containsKey(camera)) {
                adoptedDeviceCallbacks[camera] = standbyLifecycleCallbacks(camera)
            }
            standbyImageReader = reader
            imageTimestamps.clear()
            captureResultTimestamps.clear()
            receivedFrameCount = 0L
            matchedFrameCount = 0
            isDeviceTransitioning = true
            publishStatus(
                StandbyCameraStatus(
                    phase = StandbyCameraPhase.CONFIGURING,
                    activeCameraId = plan.activeOpenCameraId,
                    standbyCameraId = plan.standbyCameraId,
                    lensType = plan.lensType,
                    message = "Reconfiguring previous active device as standby",
                )
            )
            attachReaderListener(reader, openGeneration, callbackHandler)
            InstantLensDiagnostics.log(
                "STANDBY_ADOPT",
                "result=accepted deviceId=${camera.id} cameraId=${plan.standbyCameraId} " +
                    "openGeneration=$openGeneration preview=${output.size.width}x${output.size.height}",
            )
            configureStandbySession(camera, reader, plan, openGeneration)
        }
    }

    /** Route callbacks from a CameraDevice originally opened by Camera2Controller. */
    fun handleAdoptedStandbyDeviceDisconnected(camera: CameraDevice): Boolean {
        if (!adoptedStandbyDevices.contains(camera)) return false
        if (standbyCameraDevice === camera) {
            failCurrentPlan("Adopted standby camera disconnected", null)
        } else {
            InstantLensDiagnostics.log("STANDBY_ADOPT", "result=disconnected_before_session deviceId=${camera.id}")
            adoptedStandbyDevices.remove(camera)
        }
        return true
    }

    fun handleAdoptedStandbyDeviceError(camera: CameraDevice, errorCode: Int): Boolean {
        if (!adoptedStandbyDevices.contains(camera)) return false
        if (standbyCameraDevice === camera) {
            failCurrentPlan("Adopted standby camera error: $errorCode", null, errorCode)
        } else {
            InstantLensDiagnostics.log(
                "STANDBY_ADOPT",
                "result=error_before_session deviceId=${camera.id} error=$errorCode",
            )
            adoptedStandbyDevices.remove(camera)
        }
        return true
    }

    fun handleAdoptedStandbyDeviceClosed(camera: CameraDevice): Boolean {
        if (!adoptedStandbyDevices.remove(camera)) return false
        if (closingCameraDevice === camera) {
            closingCameraDevice = null
        } else if (standbyCameraDevice === camera) {
            standbyCameraDevice = null
            closeSessionAndReader()
            failCurrentPlan("Adopted standby camera closed unexpectedly", null, closeDevice = false)
        }
        if (openRequestGeneration == null && closingCameraDevice == null) {
            setDeviceTransitioning(false)
        }
        return true
    }

    fun retryAfterAppResume() {
        dispatch {
            val plan = activePlan ?: return@dispatch
            if (_status.value.phase == StandbyCameraPhase.FAILED) {
                scheduleRetry(plan, "app_resume", resetBudget = true)
            }
        }
    }

    private fun standbyLifecycleCallbacks(camera: CameraDevice) = AdoptedCameraDeviceCallbacks(
        onDisconnected = { handleAdoptedStandbyDeviceDisconnected(it) },
        onError = { device, error -> handleAdoptedStandbyDeviceError(device, error) },
        onClosed = { handleAdoptedStandbyDeviceClosed(it) },
    )

    fun prepare(
        activeCamera: CameraInfo?,
        availableCameras: List<CameraInfo>,
        activeOpenCameraId: String,
        previewSize: Size,
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
    ) {
        val plan = resolvePlan(
            activeCamera = activeCamera,
            availableCameras = availableCameras,
            activeOpenCameraId = activeOpenCameraId,
            previewSize = previewSize,
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
        if (activePlan?.key == plan.key && _status.value.phase == StandbyCameraPhase.FAILED) {
            scheduleRetry(plan, "preview_session_start", resetBudget = true)
            return
        }
        if (pendingPlan?.key == plan.key) return
        retryStates[plan.key]?.permanentlyUnsupportedReason?.let { reason ->
            logUnsupportedPlan(plan, reason, "preview_session_start")
            return
        }

        cancelScheduledRetry("standby target changed")
        pendingPlan = plan
        releaseResources("standby target changed", resetStatus = false)
        activePlan = plan
        openPendingPlanWhenIdle()
    }

    private fun resolvePlan(
        activeCamera: CameraInfo?,
        availableCameras: List<CameraInfo>,
        activeOpenCameraId: String,
        previewSize: Size,
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
        lastLoggedTimestampMismatch = null
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

        // Read and log the topology before the shared-device early return so even
        // logical Main/UW pairs are visible in the same diagnostic stream.
        // Do not require concurrentCameraIds to advertise separate camera IDs here:
        // the guarded standby open remains the existing source of truth for HAL support.
        val concurrentCameraIds = try {
            cameraManager.concurrentCameraIds
        } catch (error: Exception) {
            PLog.w(TAG, "Unable to read concurrent camera combinations; trying guarded standby open", error)
            null
        }
        val advertisedConcurrentPair = activeOpenCameraId != standbyOpenCameraId &&
            concurrentCameraIds?.any { ids ->
                ids.contains(activeOpenCameraId) && ids.contains(standbyOpenCameraId)
            } == true
        val concurrentPairs = concurrentCameraIds
            ?.map { ids -> ids.sorted().joinToString("+") }
            ?.sorted()
            ?.joinToString(";")
            ?: "unavailable"
        InstantLensDiagnostics.log(
            "CAMERA_PAIR",
            "activeCameraId=${activeCamera.cameraId} targetCameraId=${targetCamera.cameraId} " +
                "activeOpenCameraId=$activeOpenCameraId targetOpenCameraId=$standbyOpenCameraId " +
                "logicalSharedDevice=${activeOpenCameraId == standbyOpenCameraId} " +
                "pairInConcurrentCameraIds=$advertisedConcurrentPair concurrentPairs=[$concurrentPairs]",
        )

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
        activePlan = plan
        retryStates[plan.key]?.permanentlyUnsupportedReason?.let { reason ->
            logUnsupportedPlan(plan, reason, "open_plan")
            return
        }
        val output = resolvePreviewOutput(plan.standbyOpenCameraId, plan.aspectRatio)
        if (output == null) {
            markPlanUnsupported(plan, "Target camera has no usable YUV or PRIVATE preview output size")
            return
        }

        val reader = try {
            ImageReader.newInstance(output.size.width, output.size.height, output.format, 3)
        } catch (error: Exception) {
            failCurrentPlan(
                "Could not allocate standby ImageReader: " + (error.message ?: error.javaClass.simpleName),
                error,
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
        publishStatus(
            StandbyCameraStatus(
                phase = StandbyCameraPhase.OPENING,
                activeCameraId = plan.activeOpenCameraId,
                standbyCameraId = plan.standbyCameraId,
                lensType = plan.lensType,
                message = "Opening real standby camera stream",
            )
        )

        attachReaderListener(reader, openGeneration, callbackHandler)

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
                        standbyOpenedDevices += camera
                        setDeviceTransitioning(false)
                        configureStandbySession(camera, reader, plan, openGeneration)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        if (adoptedDeviceCallbacks.containsKey(camera)) {
                            val callbacks = adoptedDeviceCallbacks[camera]
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
                        if (adoptedDeviceCallbacks.containsKey(camera)) {
                            val callbacks = adoptedDeviceCallbacks[camera]
                            if (callbacks != null) callbacks.onError(camera, error)
                            else closeOpenedCamera(camera)
                            return
                        }
                        if (openRequestGeneration == openGeneration) {
                            openRequestGeneration = null
                        }
                        if (openGeneration == generation) {
                            standbyCameraDevice = camera
                            failCurrentPlan("Standby camera open error: " + error, null, error)
                        } else {
                            closeOpenedCamera(camera)
                        }
                    }

                    override fun onClosed(camera: CameraDevice) {
                        if (adoptedDeviceCallbacks.containsKey(camera)) {
                            val callbacks = adoptedDeviceCallbacks.remove(camera)
                            if (callbacks != null) {
                                callbacks.onClosed(camera)
                                return
                            }
                        }
                        standbyOpenedDevices.remove(camera)
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

    private fun resolvePreviewOutput(cameraId: String, desiredAspectRatio: Float): PreviewOutput? {
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
            val boundedSizes = sizes.filter {
                maxOf(it.width, it.height) <= MAX_PREVIEW_DIMENSION
            }.ifEmpty { sizes }
            val selectedSize = boundedSizes.minWithOrNull(
                compareBy<Size>(
                    { abs(it.width.toFloat() / it.height.toFloat() - desiredAspectRatio) },
                    { abs(it.width.toLong() * it.height.toLong() - TARGET_PREVIEW_AREA) },
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

        publishStatus(
            _status.value.copy(
                phase = StandbyCameraPhase.CONFIGURING,
                message = "Configuring secondary preview capture session",
            )
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
                        setDeviceTransitioning(false)
                        publishStatus(
                            _status.value.copy(
                                phase = StandbyCameraPhase.WAITING_FOR_FRAMES,
                                message = if (livePreviewSurface != null) {
                                    "Standby camera and live GL preview are streaming"
                                } else {
                                    "Standby session active; waiting for matching camera frames"
                                },
                            )
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
                                runCatching { session.stopRepeating() }
                                runCatching { session.close() }
                                if (standbyCaptureSession === session) standbyCaptureSession = null
                                configureStandbySession(
                                    camera,
                                    reader,
                                    plan,
                                    openGeneration,
                                    includeAlternatePreviewSurface = false,
                                )
                            } else {
                                failCurrentPlan("Could not start standby repeating request: " + error.message, error)
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runCatching { session.close() }
                        if (openGeneration == generation) {
                            if (livePreviewSurface != null) {
                                PLog.w(TAG, "Standby GL output combination rejected; retrying reader-only standby")
                                configureStandbySession(
                                    camera,
                                    reader,
                                    plan,
                                    openGeneration,
                                    includeAlternatePreviewSurface = false,
                                )
                            } else {
                                failCurrentPlan("Standby capture session configuration failed", null)
                            }
                        }
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        if (standbyCaptureSession === session) {
                            standbyCaptureSession = null
                        }
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
        val readerTimestamp = imageTimestamps.lastOrNull {
            !captureResultTimestamps.contains(it)
        }
        val sensorTimestamp = captureResultTimestamps.lastOrNull {
            !imageTimestamps.contains(it)
        }
        if (readerTimestamp != null && sensorTimestamp != null) {
            val mismatch = readerTimestamp to sensorTimestamp
            if (mismatch != lastLoggedTimestampMismatch) {
                InstantLensDiagnostics.log(
                    "TIMESTAMP_MISMATCH",
                    "cameraId=${activePlan?.standbyOpenCameraId ?: "unknown"} " +
                        "readerTimestampNs=$readerTimestamp sensorTimestampNs=$sensorTimestamp " +
                        "matchedFrames=$matchedFrameCount",
                )
                lastLoggedTimestampMismatch = mismatch
            }
        } else {
            lastLoggedTimestampMismatch = null
        }
        val current = _status.value
        if (current.phase != StandbyCameraPhase.WAITING_FOR_FRAMES &&
            current.phase != StandbyCameraPhase.READY
        ) {
            return
        }
        if (matchedFrameCount >= REQUIRED_MATCHED_FRAMES) {
            frameReadyTimeout?.let { handler?.removeCallbacks(it) }
            frameReadyTimeout = null
            activePlan?.let { retryStates.remove(it.key) }
            cancelScheduledRetry("standby became ready")
            publishStatus(
                current.copy(
                    phase = StandbyCameraPhase.READY,
                    frameCount = receivedFrameCount,
                    matchedFrameCount = matchedFrameCount,
                    message = "Standby camera is streaming real preview frames",
                )
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

    private fun failCurrentPlan(
        message: String,
        error: Throwable?,
        cameraErrorCode: Int? = null,
        closeDevice: Boolean = true,
    ) {
        val plan = activePlan
        if (error != null) {
            PLog.w(TAG, message, error)
        } else {
            PLog.w(TAG, message)
        }
        frameReadyTimeout?.let { handler?.removeCallbacks(it) }
        frameReadyTimeout = null
        pendingPlan = null
        publishStatus(
            StandbyCameraStatus(
                phase = StandbyCameraPhase.FAILED,
                activeCameraId = plan?.activeOpenCameraId,
                standbyCameraId = plan?.standbyCameraId,
                lensType = plan?.lensType,
                frameCount = receivedFrameCount,
                matchedFrameCount = matchedFrameCount,
                message = message,
            )
        )
        closeSessionAndReader()
        val device = standbyCameraDevice
        if (closeDevice && device != null) {
            closeOpenedCamera(device)
        } else if (device != null) {
            standbyCameraDevice = null
        }

        if (plan == null) {
            InstantLensDiagnostics.log("STANDBY_RETRY", "result=not_scheduled reason=no_active_plan failure=$message")
            return
        }
        val retryState = retryStates.getOrPut(plan.key) { RetryState() }
        if (cameraErrorCode == CameraDevice.ERROR_MAX_CAMERAS_IN_USE) {
            retryState.maxCameraLimitFailures += 1
            InstantLensDiagnostics.log(
                "STANDBY_RETRY",
                "event=max_cameras_in_use planKey=${plan.key} " +
                    "count=${retryState.maxCameraLimitFailures}/$MAX_CAMERA_LIMIT_FAILURES",
            )
            if (retryState.maxCameraLimitFailures >= MAX_CAMERA_LIMIT_FAILURES) {
                retryState.permanentlyUnsupportedReason =
                    "Camera HAL repeatedly returned ERROR_MAX_CAMERAS_IN_USE " +
                        "(${retryState.maxCameraLimitFailures} failures)"
                cancelScheduledRetry("plan became unsupported")
                logUnsupportedPlan(plan, retryState.permanentlyUnsupportedReason!!, "camera_error")
                return
            }
        }
        scheduleRetry(plan, message, resetBudget = false)
    }

    private fun scheduleRetry(plan: StandbyPlan, reason: String, resetBudget: Boolean) {
        val retryState = retryStates.getOrPut(plan.key) { RetryState() }
        val unsupportedReason = retryState.permanentlyUnsupportedReason
        if (unsupportedReason != null) {
            logUnsupportedPlan(plan, unsupportedReason, "retry_request")
            return
        }
        if (resetBudget) {
            cancelScheduledRetry("explicit retry trigger")
            retryState.attempts = 0
            InstantLensDiagnostics.log(
                "STANDBY_RETRY",
                "event=budget_reset trigger=$reason planKey=${plan.key}",
            )
        } else if (retryScheduledPlanKey == plan.key) {
            return
        }
        if (retryState.attempts >= MAX_AUTOMATIC_RETRIES) {
            InstantLensDiagnostics.log(
                "STANDBY_RETRY",
                "result=exhausted planKey=${plan.key} attempts=${retryState.attempts} " +
                    "trigger=$reason; waiting_for_preview_session_or_app_resume",
            )
            return
        }
        val retryIndex = retryState.attempts
        val delayMs = RETRY_BACKOFF_MS[retryIndex]
        retryState.attempts += 1
        val callbackHandler = handler ?: return
        val retry = Runnable {
            if (retryScheduledPlanKey != plan.key || activePlan?.key != plan.key ||
                _status.value.phase != StandbyCameraPhase.FAILED
            ) {
                return@Runnable
            }
            retryRunnable = null
            retryScheduledPlanKey = null
            InstantLensDiagnostics.log(
                "STANDBY_RETRY",
                "event=retry_started planKey=${plan.key} attempt=${retryState.attempts}/$MAX_AUTOMATIC_RETRIES " +
                    "afterMs=$delayMs reason=$reason",
            )
            pendingPlan = plan
            openPendingPlanWhenIdle()
        }
        retryRunnable = retry
        retryScheduledPlanKey = plan.key
        InstantLensDiagnostics.log(
            "STANDBY_RETRY",
            "event=retry_scheduled planKey=${plan.key} attempt=${retryState.attempts}/$MAX_AUTOMATIC_RETRIES " +
                "delayMs=$delayMs reason=$reason",
        )
        callbackHandler.postDelayed(retry, delayMs)
    }

    private fun cancelScheduledRetry(reason: String) {
        val retry = retryRunnable ?: return
        handler?.removeCallbacks(retry)
        InstantLensDiagnostics.log(
            "STANDBY_RETRY",
            "event=retry_cancelled planKey=${retryScheduledPlanKey ?: "unknown"} reason=$reason",
        )
        retryRunnable = null
        retryScheduledPlanKey = null
    }

    private fun markPlanUnsupported(plan: StandbyPlan, reason: String) {
        val retryState = retryStates.getOrPut(plan.key) { RetryState() }
        retryState.permanentlyUnsupportedReason = reason
        activePlan = plan
        pendingPlan = null
        publishUnavailable(
            phase = StandbyCameraPhase.FAILED,
            message = reason,
            activeCameraId = plan.activeOpenCameraId,
            standbyCameraId = plan.standbyCameraId,
            lensType = plan.lensType,
        )
        cancelScheduledRetry("plan definitively unsupported")
        logUnsupportedPlan(plan, reason, "capability_check")
    }

    private fun logUnsupportedPlan(plan: StandbyPlan, reason: String, trigger: String) {
        InstantLensDiagnostics.log(
            "STANDBY_RETRY",
            "result=permanently_unsupported planKey=${plan.key} reason=$reason trigger=$trigger",
        )
    }

    private fun attachReaderListener(reader: ImageReader, openGeneration: Long, callbackHandler: Handler) {
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
    }

    private fun releaseResources(reason: String, resetStatus: Boolean) {
        generation += 1L
        activePlan = null
        cancelScheduledRetry(reason)
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

        InstantLensDiagnostics.log("STANDBY_RELEASE", "reason=$reason resetStatus=$resetStatus")
        if (resetStatus) {
            publishStatus(StandbyCameraStatus(), transitionReason = reason)
        }
        lastLoggedTimestampMismatch = null
    }

    private fun closeSessionAndReader() {
        val session = standbyCaptureSession
        standbyCaptureSession = null
        if (session != null) {
            runCatching { session.stopRepeating() }
            runCatching { session.abortCaptures() }
            runCatching { session.close() }
        }

        val reader = standbyImageReader
        standbyImageReader = null
        if (reader != null) {
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
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

    private fun publishStatus(
        status: StandbyCameraStatus,
        transitionReason: String? = status.message,
    ) {
        val previous = _status.value
        _status.value = status
        if (previous.phase != status.phase) {
            InstantLensDiagnostics.log(
                "STANDBY_PHASE",
                "from=${previous.phase} to=${status.phase} " +
                    "reason=${transitionReason ?: "none"} " +
                    "activeCameraId=${status.activeCameraId ?: "unknown"} " +
                    "standbyCameraId=${status.standbyCameraId ?: "unknown"} " +
                    "frames=${status.frameCount} matchedFrames=${status.matchedFrameCount}",
            )
        }
    }

    private fun publishUnavailable(
        phase: StandbyCameraPhase,
        message: String,
        activeCameraId: String? = null,
        standbyCameraId: String? = null,
        lensType: LensType? = null,
    ) {
        publishStatus(
            StandbyCameraStatus(
                phase = phase,
                activeCameraId = activeCameraId,
                standbyCameraId = standbyCameraId,
                lensType = lensType,
                message = message,
            )
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
