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
        val onSwitch: () -> Unit,
    )
    private var pendingRequest: Request? = null

    fun switchLens(cameraId: String, targetZoom: Float, onSwitch: () -> Unit) {
        require(targetZoom.isFinite() && targetZoom > 0f)
        pendingRequest = Request(cameraId, targetZoom, onSwitch)
        if (switchPending && job?.isActive == true) return

        job?.cancel()
        isActive = true
        switchPending = true
        job = scope.launch {
            var livePreview: CameraGLSurfaceView? = null
            try {
                val request = pendingRequest
                pendingRequest = null
                if (request == null) {
                    isActive = false
                    switchPending = false
                    return@launch
                }

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
                val canStartLiveHandoff = isRearMainOrUltraWide &&
                    livePreview?.canUseLiveLensHandoff == true

                val previousTimestamp = before.previewFirstFrameTimestampNs
                val useLiveHandoff = if (canStartLiveHandoff) {
                    withTimeoutOrNull(900L) {
                        livePreview?.beginLiveLensHandoffAndWait()
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
                            withTimeoutOrNull(900L) {
                                preview.awaitPreviewFrames(
                                    minimumTimestampNs = readyState.previewFirstFrameTimestampNs!!,
                                    requiredFrameCount = 18,
                                )
                            }
                        }
                        withTimeoutOrNull(1_500L) {
                            preview.finishLiveLensHandoffAndWait()
                        }
                    }
                }
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
                    switchLens(next.cameraId, next.targetZoom, next.onSwitch)
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
