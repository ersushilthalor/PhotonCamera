package com.hinnka.mycamera.ui.camera

import com.hinnka.mycamera.camera.LensType
import com.hinnka.mycamera.utils.PLog
import com.hinnka.mycamera.viewmodel.CameraViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Coordinates lens handoff using the live secondary Camera2 stream.
 *
 * There is deliberately no PixelCopy/snapshot overlay here. On devices which support the
 * simultaneous main/ultra-wide pair, the renderer first blends to the warm standby stream,
 * then Camera2 promotes that device, and finally the renderer blends back to the newly active
 * primary stream. Unsupported camera pairs fall back to a direct switch and first-frame wait.
 */
internal class LensSwitchTransitionState(
    private val scope: CoroutineScope,
    private val viewModel: CameraViewModel,
) {
    var isActive: Boolean = false
        private set

    private var job: Job? = null
    private var switchPending = false
    private data class Request(
        val cameraId: String,
        val targetZoom: Float,
        val forceImmediateHandoff: Boolean,
        val onComplete: (() -> Unit)?,
        val onSwitch: () -> Unit,
    )
    private var pendingRequest: Request? = null

    fun switchLens(
        cameraId: String,
        targetZoom: Float,
        forceImmediateHandoff: Boolean = false,
        onComplete: (() -> Unit)? = null,
        onSwitch: () -> Unit,
    ) {
        require(targetZoom.isFinite() && targetZoom > 0f)
        pendingRequest = Request(cameraId, targetZoom, forceImmediateHandoff, onComplete, onSwitch)
        if (switchPending && job?.isActive == true) return

        job?.cancel()
        isActive = true
        switchPending = true
        job = scope.launch {
            var livePreview: CameraGLSurfaceView? = null
            var transitionCompleted = false
            var completedRequest: Request? = null
            try {
                val request = pendingRequest
                pendingRequest = null
                if (request == null) {
                    isActive = false
                    switchPending = false
                    return@launch
                }
                completedRequest = request

                val before = viewModel.state.value
                val startingZoomByMain = viewModel.zoomRatioByMain
                val currentCamera = before.getCurrentCameraInfo()
                val targetCamera = before.availableCameras.firstOrNull {
                    it.cameraId == request.cameraId
                }
                val currentLensType = currentCamera?.lensType
                val targetLensType = targetCamera?.lensType
                val isRearMainOrUltraWide =
                    (currentLensType == LensType.BACK_MAIN ||
                        currentLensType == LensType.BACK_ULTRA_WIDE) &&
                        (targetLensType == LensType.BACK_MAIN ||
                            targetLensType == LensType.BACK_ULTRA_WIDE) &&
                        currentLensType != targetLensType
                livePreview = viewModel.glSurfaceView
                val overlapEnabled = viewModel.lensSwitchOverlapAnimationEnabled.value
                // A direct ultra-wide/main stop tap can use the already-acquired standby frame
                // immediately. The renderer holds it while Camera2 promotes the target device
                // and waits for the first real primary preview frame.
                // Ultra-wide's visible stop comes from its physical focal length (for example
                // 0.54x or 0.6x), so never hard-code 0.5x for this handoff.
                val targetStopZoom = targetCamera?.displayIntrinsicZoomRatio?.takeIf { it > 0f } ?: 1f
                val isDirectUltraWideToMainTap =
                    currentLensType == LensType.BACK_ULTRA_WIDE &&
                        targetLensType == LensType.BACK_MAIN &&
                        abs(request.targetZoom - targetStopZoom) < 0.05f
                val isDirectMainToUltraWideTap =
                    currentLensType == LensType.BACK_MAIN &&
                        targetLensType == LensType.BACK_ULTRA_WIDE &&
                        abs(startingZoomByMain - 1f) < 0.05f &&
                        abs(request.targetZoom - targetStopZoom) < 0.05f
                val isDirectImmediateLensTap =
                    request.forceImmediateHandoff ||
                        isDirectUltraWideToMainTap ||
                        isDirectMainToUltraWideTap
                val overlapDurationFrames = if (isDirectImmediateLensTap) {
                    1
                } else {
                    (viewModel.lensSwitchOverlapDurationSec.value * 30f).roundToInt().coerceIn(2, 30)
                }
                livePreview?.setLiveHandoffFrameCount(overlapDurationFrames)
                // Immediate stop taps are a source promotion, not an overlap animation.
                // Keep them enabled even when the optional crossfade setting is disabled.
                val canStartLiveHandoff = (overlapEnabled || isDirectImmediateLensTap) &&
                    isRearMainOrUltraWide &&
                    livePreview?.canUseLiveLensHandoff == true

                val previousTimestamp = before.previewFirstFrameTimestampNs
                val useLiveHandoff = if (canStartLiveHandoff) {
                    withTimeoutOrNull(900L) {
                        if (isDirectImmediateLensTap) {
                            livePreview?.beginImmediateLiveLensHandoffAndWait()
                        } else {
                            livePreview?.beginLiveLensHandoffAndWait()
                        }
                        true
                    } ?: false
                } else false
                if (canStartLiveHandoff && !useLiveHandoff) {
                    livePreview?.cancelLiveLensHandoff()
                }

                request.onSwitch()

                val readyState = withTimeoutOrNull(3_500L) {
                    viewModel.state.first {
                        it.currentCameraId == request.cameraId &&
                            it.isPreviewActive &&
                            it.previewFirstFrameTimestampNs != null &&
                            it.previewFirstFrameTimestampNs != previousTimestamp
                    }
                }
                if (readyState == null) {
                    PLog.w("LensSwitchTransition", "Timed out waiting for active preview: ${request.cameraId}")
                    livePreview?.cancelLiveLensHandoff()
                    isActive = false
                    switchPending = false
                    return@launch
                }

                val preview = livePreview ?: viewModel.glSurfaceView
                if (preview != null) {
                    withTimeoutOrNull(1_500L) {
                        preview.awaitPreviewFrame(readyState.previewFirstFrameTimestampNs!!)
                    }

                    if (useLiveHandoff) {
                        // Match VCamera's 16-frame warm-up requirement on the direct 0.5x ↔
                        // 2x/3x handoff. Other lens transitions use the same live crossfade
                        // without imposing this additional delay.
                        if (isFrameBasedHandoffEligible(startingZoomByMain, request.targetZoom)) {
                            // The first target-lens frame was already confirmed above. Hold
                            // the outgoing image until 16 additional, genuinely rendered
                            // target-camera frames have completed the preview pipeline.
                            // 900 ms was too short on slower/high-resolution preview paths
                            // and the old timeout result was ignored, so the hand-off often
                            // proceeded without completing its frame warm-up.
                            val frameWarmupCompleted = withTimeoutOrNull(3_000L) {
                                preview.awaitPreviewFrames(requiredFrameCount = 16)
                                true
                            } ?: false
                            if (!frameWarmupCompleted) {
                                PLog.w(
                                    "LensSwitchTransition",
                                    "Frame-based handoff timed out before 16 target-camera preview frames; " +
                                        "continuing with the normal live handoff"
                                )
                            } else {
                                PLog.d(
                                    "LensSwitchTransition",
                                    "Frame-based handoff completed after 16 target-camera preview frames"
                                )
                            }
                        }
                        withTimeoutOrNull(1_500L) {
                            preview.finishLiveLensHandoffAndWait()
                        }
                    }
                }
                transitionCompleted = true
            } catch (error: CancellationException) {
                livePreview?.cancelLiveLensHandoff()
                throw error
            } catch (error: Exception) {
                PLog.e("LensSwitchTransition", "Live lens handoff failed", error)
                livePreview?.cancelLiveLensHandoff()
            } finally {
                isActive = false
                switchPending = false
                job = null
                // A rapid later selection replaces the pending target while this switch is
                // running. Start that request only after the current camera state is settled.
                val next = pendingRequest
                if (next != null && scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
                    pendingRequest = null
                    if (viewModel.state.value.currentCameraId == next.cameraId) {
                        // The camera switch already promoted this lens; only the zoom value changed.
                        // Replaying the switch would close and cold-open the same camera again.
                        viewModel.setZoomRatio(next.targetZoom)
                        next.onComplete?.invoke()
                    } else {
                        switchLens(
                            cameraId = next.cameraId,
                            targetZoom = next.targetZoom,
                            forceImmediateHandoff = next.forceImmediateHandoff,
                            onComplete = next.onComplete,
                        ) {
                            next.onSwitch()
                        }
                    }
                }
            }
            if (transitionCompleted) {
                try {
                    completedRequest?.onComplete?.invoke()
                } catch (error: Exception) {
                    PLog.e("LensSwitchTransition", "Lens switch completion callback failed", error)
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        pendingRequest = null
        switchPending = false
        isActive = false
        viewModel.glSurfaceView?.cancelLiveLensHandoff()
    }

    fun cancelPendingSwitch() {
        pendingRequest = null
    }

    private fun isFrameBasedHandoffEligible(fromZoom: Float, targetZoom: Float): Boolean {
        fun isHalfX(value: Float) = abs(value - 0.5f) < 0.025f
        fun isTele(value: Float) =
            abs(value - 2f) < 0.05f || abs(value - 3f) < 0.05f
        return (isHalfX(fromZoom) && isTele(targetZoom)) ||
            (isTele(fromZoom) && isHalfX(targetZoom))
    }
}
