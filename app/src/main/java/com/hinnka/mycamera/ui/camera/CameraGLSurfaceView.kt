package com.hinnka.mycamera.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.hinnka.mycamera.camera.MeteringMode
import com.hinnka.mycamera.data.LiquidGlassSettings
import com.hinnka.mycamera.livephoto.LivePhotoRecorder
import com.hinnka.mycamera.lut.LutConfig
import com.hinnka.mycamera.lut.LutRenderer
import com.hinnka.mycamera.lut.PreviewCaptureSource
import com.hinnka.mycamera.model.ColorRecipeParams
import com.hinnka.mycamera.preview.EyeFocusPreviewFrame
import com.hinnka.mycamera.preview.EyeFocusProcessingTiming
import com.hinnka.mycamera.raw.RawEnginePreviewPlan
import com.hinnka.mycamera.screencapture.PhantomPipCrop
import com.hinnka.mycamera.stabilization.DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD
import com.hinnka.mycamera.stabilization.RealtimeStabilizationCoordinator
import com.hinnka.mycamera.stabilization.StabilizationUseCase
import com.hinnka.mycamera.stabilization.normalizeStabilizationLookahead
import com.hinnka.mycamera.stabilization.normalizeStabilizationStrength
import com.hinnka.mycamera.utils.PLog
import com.hinnka.mycamera.video.VideoLogProfile
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Camera preview output backed by TextureView instead of SurfaceView.
 *
 * The GLES renderer and Camera2 input SurfaceTexture remain unchanged. Only the EGL
 * window target changes: drawing into TextureView makes the already-rendered preview
 * participate in the Compose view hierarchy, so Prismal can capture it as a backdrop.
 */
class CameraGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "CameraGLSurfaceView"
        private const val EGL_CONTEXT_VERSION = 3
        private const val SURFACE_DESTROY_TIMEOUT_SECONDS = 3L
    }

    private var renderer = LutRenderer(context.applicationContext)
    private var rendererReleased = false

    private var glThread: HandlerThread? = null
    @Volatile private var glHandler: Handler? = null
    private val pendingGlEvents = ConcurrentLinkedQueue<Runnable>()
    @Volatile private var eglReady = false
    @Volatile private var surfaceAvailable = false
    @Volatile private var renderingPaused = false
    private val surfaceCleanupDispatched = AtomicBoolean(true)

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglConfig: EGLConfig? = null
    private var eglWindowSurface: EGLSurface? = null

    @Volatile private var currentSurface: Surface? = null
    private var currentStabilizationCoordinator: RealtimeStabilizationCoordinator? = null
    private var currentPreviewStabilizationUseCase: StabilizationUseCase? = null
    private var currentPreviewStabilizationStrength = 0f
    private var currentPreviewStabilizationLookahead = DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD
    @Volatile private var eyeFocusInputCallback: ((EyeFocusPreviewFrame) -> Unit)? = null

    var onHistogramUpdated: ((IntArray) -> Unit)? = null
    var onMeteringUpdated: ((Double, Double) -> Unit)? = null
    var onHighlightPointUpdated: ((Float, Float) -> Unit)? = null

    var onEyeFocusInputAvailable: ((EyeFocusPreviewFrame) -> Unit)?
        get() = eyeFocusInputCallback
        set(value) {
            eyeFocusInputCallback = value
            applyEyeFocusCallback()
        }

    var onSurfaceReady: ((Surface) -> Unit)? = null
    var onSurfaceDestroyed: (() -> Unit)? = null
    var onFirstPreviewFrame: (() -> Unit)? = null

    init {
        isOpaque = true
        surfaceTextureListener = this
        ensureGlThread()
        configureRendererCallbacks()
        PLog.d(TAG, "TextureView-backed Camera GLES preview initialized")
    }

    private fun ensureGlThread() {
        if (glThread?.isAlive == true && glHandler != null) return
        synchronized(this) {
            if (glThread?.isAlive == true && glHandler != null) return
            val thread = HandlerThread("CameraPreviewEgl")
            thread.start()
            glThread = thread
            glHandler = Handler(thread.looper)
        }
    }

    private fun ensureRenderer() {
        if (!rendererReleased) return
        renderer = LutRenderer(context.applicationContext)
        rendererReleased = false
        configureRendererCallbacks()
        currentStabilizationCoordinator?.let(renderer::setStabilizationCoordinator)
    }

    private fun configureRendererCallbacks() {
        renderer.onSurfaceTextureAvailable = { cameraTexture ->
            val surface = Surface(cameraTexture)
            currentSurface?.release()
            currentSurface = surface
            post {
                if (surfaceAvailable && currentSurface === surface) {
                    onSurfaceReady?.invoke(surface)
                } else {
                    surface.release()
                }
            }
        }
        renderer.onRequestRender = { requestRender() }
        renderer.onHistogramUpdated = { histogram -> onHistogramUpdated?.invoke(histogram) }
        renderer.onMeteringUpdated = { totalWeight, weightedSumLuminance ->
            onMeteringUpdated?.invoke(totalWeight, weightedSumLuminance)
        }
        renderer.onHighlightPointUpdated = { hx, hy -> onHighlightPointUpdated?.invoke(hx, hy) }
        renderer.onFirstFrameRendered = { post { onFirstPreviewFrame?.invoke() } }
        applyEyeFocusCallback()
    }

    private fun applyEyeFocusCallback() {
        renderer.onEyeFocusInputAvailable = if (eyeFocusInputCallback == null) {
            renderer.isEyeFocusBusy = false
            null
        } else {
            { frame ->
                post {
                    val callback = eyeFocusInputCallback
                    if (callback == null) {
                        frame.bitmap.recycle()
                    } else {
                        callback(frame)
                    }
                }
            }
        }
    }

    /**
     * GLSurfaceView-compatible queue API. GL work is held until the TextureView's
     * EGL window surface is current, then executed on the single EGL thread.
     */
    fun queueEvent(runnable: Runnable) {
        ensureGlThread()
        val handler = glHandler ?: return
        handler.post {
            if (eglReady && makeEglCurrent()) {
                runCatching { runnable.run() }
                    .onFailure { PLog.e(TAG, "Queued GL event failed", it) }
            } else {
                pendingGlEvents.add(runnable)
            }
        }
    }

    /** Render one frame when the camera SurfaceTexture or a control requests it. */
    fun requestRender() {
        ensureGlThread()
        glHandler?.post { drawFrame() }
    }

    private fun drawFrame() {
        if (!eglReady || !surfaceAvailable || renderingPaused) return
        if (!makeEglCurrent()) return
        try {
            renderer.onDrawFrame(null)
            val display = eglDisplay ?: return
            val surface = eglWindowSurface ?: return
            if (!EGL14.eglSwapBuffers(display, surface)) {
                val error = EGL14.eglGetError()
                if (error != EGL14.EGL_BAD_SURFACE && error != EGL14.EGL_CONTEXT_LOST) {
                    PLog.w(TAG, "eglSwapBuffers failed: 0x" + error.toString(16))
                }
            }
        } catch (error: RuntimeException) {
            PLog.e(TAG, "Camera preview frame render failed", error)
        }
    }

    private fun makeEglCurrent(): Boolean {
        val display = eglDisplay ?: return false
        val context = eglContext ?: return false
        val surface = eglWindowSurface ?: return false
        return EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    private fun initializeEglWindow(surfaceTexture: SurfaceTexture, width: Int, height: Int): Boolean {
        releaseEglWindow()

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            PLog.e(TAG, "eglGetDisplay failed: 0x" + EGL14.eglGetError().toString(16))
            return false
        }

        val versions = IntArray(2)
        if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            PLog.e(TAG, "eglInitialize failed: 0x" + EGL14.eglGetError().toString(16))
            return false
        }

        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        val choseConfig = EGL14.eglChooseConfig(
            display, configAttributes, 0, configs, 0, configs.size, configCount, 0
        )
        val config = configs.firstOrNull()
        if (!choseConfig || config == null || configCount[0] <= 0) {
            PLog.e(TAG, "No EGL config supports an OpenGL ES 3 window surface")
            EGL14.eglTerminate(display)
            return false
        }

        val contextAttributes = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, EGL_CONTEXT_VERSION,
            EGL14.EGL_NONE
        )
        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0
        )
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            PLog.e(TAG, "eglCreateContext failed: 0x" + EGL14.eglGetError().toString(16))
            EGL14.eglTerminate(display)
            return false
        }

        val windowSurface = EGL14.eglCreateWindowSurface(
            display, config, surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (windowSurface == null || windowSurface == EGL14.EGL_NO_SURFACE) {
            PLog.e(TAG, "eglCreateWindowSurface failed: 0x" + EGL14.eglGetError().toString(16))
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            return false
        }

        if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
            PLog.e(TAG, "eglMakeCurrent failed: 0x" + EGL14.eglGetError().toString(16))
            EGL14.eglDestroySurface(display, windowSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            return false
        }

        eglDisplay = display
        eglContext = context
        eglConfig = config
        eglWindowSurface = windowSurface
        eglReady = true
        EGL14.eglSwapInterval(display, 1)

        // LutRenderer still owns the Camera2 input OES texture and its SurfaceTexture.
        // Only its output window changed from GLSurfaceView's Surface to TextureView.
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, width.coerceAtLeast(1), height.coerceAtLeast(1))
        drainPendingGlEvents()
        drawFrame()
        return true
    }

    private fun drainPendingGlEvents() {
        while (true) {
            val event = pendingGlEvents.poll() ?: break
            runCatching { event.run() }
                .onFailure { PLog.e(TAG, "Pending GL event failed", it) }
        }
    }

    private fun releaseEglWindow() {
        val display = eglDisplay
        if (display != null && display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            eglWindowSurface?.let { surface ->
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            }
            eglContext?.let { context ->
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            }
            EGL14.eglTerminate(display)
        }
        eglWindowSurface = null
        eglContext = null
        eglConfig = null
        eglDisplay = null
        eglReady = false
    }

    private fun destroyRendererSurface() {
        if (!surfaceCleanupDispatched.compareAndSet(false, true)) return
        surfaceAvailable = false

        // Notify Camera2 before releasing the input SurfaceTexture, matching the old
        // GLSurfaceView teardown order.
        runCatching { onSurfaceDestroyed?.invoke() }
            .onFailure { PLog.e(TAG, "Preview surface destroy callback failed", it) }
        currentSurface?.release()
        currentSurface = null

        val handler = glHandler
        if (handler == null) {
            rendererReleased = true
            return
        }

        val finished = CountDownLatch(1)
        handler.post {
            try {
                if (eglReady) makeEglCurrent()
                renderer.release()
                releaseEglWindow()
                rendererReleased = true
                pendingGlEvents.clear()
            } catch (error: RuntimeException) {
                PLog.e(TAG, "Failed to release camera preview renderer", error)
            } finally {
                finished.countDown()
            }
        }
        try {
            if (!finished.await(SURFACE_DESTROY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                PLog.w(TAG, "Timed out while releasing the TextureView EGL surface")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // Keep the public configuration API used by CameraPreviewGL and CameraViewModel.

    fun setPreviewSize(width: Int, height: Int) {
        queueEvent { renderer.setPreviewSize(width, height) }
    }

    fun markPreviewTransitionStart(
        sessionReconfigureStartedAtElapsedMs: Long,
        modeSwitchTapAtElapsedMs: Long?,
    ) {
        queueEvent {
            renderer.markPreviewTransitionStart(
                sessionReconfigureStartedAtElapsedMs = sessionReconfigureStartedAtElapsedMs,
                modeSwitchTapAtElapsedMs = modeSwitchTapAtElapsedMs,
            )
        }
    }

    fun setStabilizationInputSize(width: Int, height: Int) {
        queueEvent { renderer.setStabilizationInputSize(width, height) }
    }

    fun setCaptureSize(width: Int, height: Int) {
        queueEvent { renderer.setCaptureSize(width, height) }
    }

    fun setSensorOrientation(orientation: Int) {
        queueEvent { renderer.setSensorOrientation(orientation) }
    }

    fun setCalibrationOffset(offset: Int) {
        queueEvent { renderer.setCalibrationOffset(offset) }
    }

    fun setDeviceRotation(degrees: Int) {
        queueEvent { renderer.setDeviceRotation(degrees) }
    }

    fun setLensFacing(facing: Int) {
        queueEvent { renderer.setLensFacing(facing) }
    }

    fun setCaptureAspectRatio(aspectRatio: Float) {
        queueEvent { renderer.setCaptureAspectRatio(aspectRatio) }
    }

    fun setStabilizationCoordinator(coordinator: RealtimeStabilizationCoordinator?) {
        if (currentStabilizationCoordinator === coordinator) return
        currentStabilizationCoordinator = coordinator
        queueEvent {
            renderer.setStabilizationCoordinator(coordinator)
            requestRender()
        }
    }

    fun setPreviewStabilization(
        useCase: StabilizationUseCase?,
        strength: Float,
        lookaheadFrames: Int = DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD,
    ) {
        val normalizedStrength = normalizeStabilizationStrength(strength)
        val normalizedLookahead = normalizeStabilizationLookahead(lookaheadFrames)
        if (currentPreviewStabilizationUseCase == useCase &&
            kotlin.math.abs(currentPreviewStabilizationStrength - normalizedStrength) <= 0.0001f &&
            currentPreviewStabilizationLookahead == normalizedLookahead
        ) {
            return
        }
        currentPreviewStabilizationUseCase = useCase
        currentPreviewStabilizationStrength = normalizedStrength
        currentPreviewStabilizationLookahead = normalizedLookahead
        queueEvent {
            renderer.setPreviewStabilization(useCase, normalizedStrength, normalizedLookahead)
            requestRender()
        }
    }

    fun setMeteringPoint(point: PointF?) {
        renderer.meteringPoint = point
    }

    fun setMeteringMode(mode: MeteringMode) {
        renderer.meteringMode = mode
    }

    fun setMeteringEnabled(enabled: Boolean) {
        renderer.meteringEnabled = enabled
    }

    fun setLut(lutConfig: LutConfig?) {
        queueEvent {
            renderer.setLut(lutConfig)
            requestRender()
        }
    }

    fun setEnginePreview(plan: RawEnginePreviewPlan?) {
        if (renderer.setEnginePreview(plan)) requestRender()
    }

    fun setBaselineLut(lutConfig: LutConfig?) {
        queueEvent {
            renderer.setBaselineLut(lutConfig)
            requestRender()
        }
    }

    fun setLutEnabled(enabled: Boolean) {
        renderer.lutEnabled = enabled
        requestRender()
    }

    fun setBaselineLutEnabled(enabled: Boolean) {
        renderer.baselineLutEnabled = enabled
        requestRender()
    }

    fun setAutoFocus(auto: Boolean) {
        renderer.isAutoFocus = auto
        requestRender()
    }

    fun setFocusPeakingEnabled(enabled: Boolean) {
        renderer.focusPeakingEnabled = enabled
        requestRender()
    }

    fun setEyeFocusBusy(busy: Boolean) {
        renderer.isEyeFocusBusy = busy
    }

    fun updateEyeFocusTiming(timing: EyeFocusProcessingTiming) {
        renderer.updateEyeFocusTiming(timing)
    }

    fun getLutIntensity(): Float = renderer.lutIntensity

    fun isLutEnabled(): Boolean = renderer.lutEnabled

    fun setColorRecipeEnabled(enabled: Boolean) {
        renderer.colorRecipeEnabled = enabled
        requestRender()
    }

    fun setBaselineColorRecipeEnabled(enabled: Boolean) {
        renderer.baselineColorRecipeEnabled = enabled
        requestRender()
    }

    fun setParams(params: ColorRecipeParams) {
        renderer.setRecipeParams(params)
        requestRender()
    }

    fun setBaselineParams(params: ColorRecipeParams) {
        renderer.updateBaselineRecipeParams(params)
        requestRender()
    }

    fun requestRenderFrame() {
        requestRender()
    }

    fun getSurfaceTexture(): SurfaceTexture? = renderer.getSurfaceTexture()

    fun getRenderSurface(): Surface? = currentSurface

    fun setLiquidGlassOverlay(
        bounds: FloatArray?,
        cornerRadiusPx: Float,
        refractionHeightPx: Float,
        refractionAmountPx: Float,
    ) = setLiquidGlassOverlays(
        bounds,
        cornerRadiusPx,
        LiquidGlassSettings().copy(
            refractionHeightDp = refractionHeightPx,
            refractionAmountDp = refractionAmountPx
        )
    )

    fun setLiquidGlassOverlays(
        bounds: FloatArray?,
        cornerRadiusPx: Float,
        refractionHeightPx: Float,
        refractionAmountPx: Float,
    ) = setLiquidGlassOverlays(
        bounds,
        cornerRadiusPx,
        LiquidGlassSettings().copy(
            refractionHeightDp = refractionHeightPx,
            refractionAmountDp = refractionAmountPx
        )
    )

    fun setLiquidGlassOverlays(
        bounds: FloatArray?,
        cornerRadiusPx: Float,
        settings: LiquidGlassSettings,
    ) {
        val safeBounds = bounds?.copyOf()
        val safeSettings = settings.sanitized()
        queueEvent {
            renderer.setLiquidGlassOverlays(safeBounds, cornerRadiusPx, safeSettings)
            requestRender()
        }
    }

    fun setSourceCrop(crop: PhantomPipCrop) {
        queueEvent {
            renderer.setSourceCrop(crop)
            requestRender()
        }
    }

    fun capturePreviewFrame(callback: (Bitmap) -> Unit) {
        capturePreviewFrameInternal(
            maxLongEdge = null,
            source = PreviewCaptureSource.FinalDisplay,
            requestRenderImmediately = true
        ) { bitmap ->
            bitmap?.let(callback)
        }
    }

    fun capturePreviewFrame(maxLongEdge: Int, callback: (Bitmap) -> Unit) {
        capturePreviewFrameInternal(
            maxLongEdge = maxLongEdge,
            source = PreviewCaptureSource.FinalDisplay,
            requestRenderImmediately = true
        ) { bitmap ->
            bitmap?.let(callback)
        }
    }

    fun captureNextPreviewFrame(maxLongEdge: Int, callback: (Bitmap) -> Unit) {
        capturePreviewFrameInternal(
            maxLongEdge = maxLongEdge,
            source = PreviewCaptureSource.FinalDisplay,
            requestRenderImmediately = false
        ) { bitmap ->
            bitmap?.let(callback)
        }
    }

    suspend fun awaitPreviewFrame(minimumTimestampNs: Long) {
        var listener: ((Long) -> Unit)? = null
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val callback: (Long) -> Unit = { timestamp ->
                    if (timestamp >= minimumTimestampNs) {
                        renderer.onPreviewFrameRendered = null
                        post {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
                listener = callback
                queueEvent {
                    if (continuation.isActive) {
                        renderer.onPreviewFrameRendered = callback
                        requestRender()
                    }
                }
            }
        } finally {
            val installedListener = listener
            queueEvent {
                if (renderer.onPreviewFrameRendered === installedListener) {
                    renderer.onPreviewFrameRendered = null
                }
            }
        }
    }

    fun captureOriginalPreviewFrame(callback: (Bitmap?) -> Unit) {
        capturePreviewFrameInternal(
            maxLongEdge = null,
            source = PreviewCaptureSource.Original,
            requestRenderImmediately = true,
            callback = callback
        )
    }

    fun capturePhotoPreviewFrames(
        onDisplayCaptured: (Bitmap) -> Unit,
        onOriginalCaptured: (Bitmap) -> Unit,
    ) {
        queueEvent {
            renderer.capturePreviewFrame(source = PreviewCaptureSource.FinalDisplay) { bitmap ->
                post { onDisplayCaptured(bitmap) }
            }
            renderer.capturePreviewFrame(source = PreviewCaptureSource.Original) { bitmap ->
                post { onOriginalCaptured(bitmap) }
            }
            requestRender()
        }
    }

    private fun capturePreviewFrameInternal(
        maxLongEdge: Int?,
        source: PreviewCaptureSource,
        requestRenderImmediately: Boolean,
        callback: (Bitmap?) -> Unit
    ) {
        queueEvent {
            val onCaptured: (Bitmap) -> Unit = { bitmap ->
                post { callback(bitmap) }
            }
            if (maxLongEdge != null) {
                renderer.capturePreviewFrame(maxLongEdge, source, onCaptured)
            } else {
                renderer.capturePreviewFrame(source = source, onCaptured = onCaptured)
            }
            if (requestRenderImmediately) requestRender()
        }
    }

    fun setLivePhotoRecorder(recorder: LivePhotoRecorder?) {
        renderer.livePhotoRecorder = recorder
    }

    fun setVideoLogProfile(profile: VideoLogProfile) {
        renderer.videoLogProfile = profile
        requestRender()
    }

    fun onPause() {
        renderingPaused = true
        renderer.setRenderingPaused(true)
    }

    fun onResume() {
        renderingPaused = false
        renderer.setRenderingPaused(false)
        requestRender()
    }

    fun restoreRenderStateAfterResume() {
        queueEvent {
            renderer.restoreLutTexturesAfterResume()
            requestRender()
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        ensureGlThread()
        ensureRenderer()
        surfaceAvailable = true
        surfaceCleanupDispatched.set(false)
        val handler = glHandler ?: return
        handler.post {
            if (!initializeEglWindow(surface, width, height)) {
                surfaceAvailable = false
                PLog.e(TAG, "Unable to initialize the TextureView EGL window")
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        glHandler?.post {
            if (eglReady && makeEglCurrent()) {
                renderer.onSurfaceChanged(null, width.coerceAtLeast(1), height.coerceAtLeast(1))
                drawFrame()
            }
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        destroyRendererSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureGlThread()
        ensureRenderer()
    }

    override fun onDetachedFromWindow() {
        destroyRendererSurface()
        val thread = glThread
        glHandler = null
        glThread = null
        thread?.quitSafely()
        super.onDetachedFromWindow()
        PLog.d(TAG, "onDetachedFromWindow")
    }
}
