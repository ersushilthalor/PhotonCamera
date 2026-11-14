package com.hinnka.mycamera.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import android.opengl.*
import com.hinnka.mycamera.utils.PLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class DualCaptureLayout { PICTURE_IN_PICTURE, STACKED }

data class DualCaptureCameraSource(
    val cameraId: String,
    val sensorOrientation: Int,
    val streamSizes: Map<VideoResolutionPreset, Size>
)

data class DualCaptureResolutionOption(
    val preset: VideoResolutionPreset,
    val outputSize: Size,
    val backInputSize: Size,
    val frontInputSize: Size
) {
    fun describe(): String =
        "Rear ${backInputSize.width}×${backInputSize.height} · Front ${frontInputSize.width}×${frontInputSize.height} · Output ${outputSize.width}×${outputSize.height}"
}

data class DualCaptureSupport(
    val backCamera: DualCaptureCameraSource,
    val frontCamera: DualCaptureCameraSource,
    val options: List<DualCaptureResolutionOption>,
    val portraitAspectRatio: Float,
    val fps: Int,
    val codecMime: String
) {
    val defaultOption: DualCaptureResolutionOption
        get() = options.firstOrNull { it.preset == VideoResolutionPreset.FHD_1080P } ?: options.first()
}

data class DualCaptureCapabilityResult(val support: DualCaptureSupport?, val message: String)

object DualCaptureCapabilities {
    fun resolve(
        context: Context,
        aspectRatio: VideoAspectRatio,
        openGatePortraitAspectRatio: Float,
        fps: Int,
        codecMime: String
    ): DualCaptureCapabilityResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return DualCaptureCapabilityResult(null, "Simultaneous front + rear capture requires Android 11 or newer.")
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sets = try {
            manager.concurrentCameraIds
        } catch (error: Exception) {
            PLog.e("DualCaptureCapabilities", "Concurrent camera query failed", error)
            return DualCaptureCapabilityResult(null, "Android could not read concurrent-camera capabilities.")
        }
        if (sets.isEmpty()) {
            return DualCaptureCapabilityResult(null, "This device does not expose a simultaneous front + rear camera pair.")
        }

        val ratio = aspectRatio.getPortraitAspectRatio(openGatePortraitAspectRatio).coerceIn(0.1f, 1f)
        for (ids in sets) {
            val metadata = ids.mapNotNull { id ->
                runCatching { id to manager.getCameraCharacteristics(id) }.getOrNull()
            }.toMap()
            val backId = metadata.entries.firstOrNull {
                it.value.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }?.key ?: continue
            val frontId = metadata.entries.firstOrNull {
                it.value.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }?.key ?: continue
            val back = metadata.getValue(backId)
            val front = metadata.getValue(frontId)
            val backMap = back.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val frontMap = front.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val backSizes = backMap.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            val frontSizes = frontMap.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            if (backSizes.isEmpty() || frontSizes.isEmpty()) continue

            val options = VideoResolutionPreset.entries.mapNotNull { preset ->
                val output = preset.resolveOutputSize(ratio)
                if (!hasSurfaceEncoder(codecMime, output, fps)) return@mapNotNull null
                val backInput = chooseInput(backSizes, preset, ratio)
                    ?: return@mapNotNull null
                val frontInput = chooseInput(frontSizes, preset, ratio)
                    ?: return@mapNotNull null
                DualCaptureResolutionOption(preset, output, backInput, frontInput)
            }
            if (options.isEmpty()) continue

            return DualCaptureCapabilityResult(
                DualCaptureSupport(
                    backCamera = DualCaptureCameraSource(
                        backId,
                        back.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                        options.associate { it.preset to it.backInputSize }
                    ),
                    frontCamera = DualCaptureCameraSource(
                        frontId,
                        front.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                        options.associate { it.preset to it.frontInputSize }
                    ),
                    options = options,
                    portraitAspectRatio = ratio,
                    fps = fps,
                    codecMime = codecMime
                ),
                ""
            )
        }
        return DualCaptureCapabilityResult(
            null,
            "A concurrent front + rear pair exists, but no common camera stream and hardware encoder combination supports the selected frame rate."
        )
    }

    private fun chooseInput(
        sizes: List<Size>,
        preset: VideoResolutionPreset,
        targetPortraitRatio: Float
    ): Size? = sizes.filter {
        min(it.width, it.height) >= preset.shortEdge &&
            max(it.width, it.height) >= preset.longEdge
    }.minWithOrNull(
        compareBy<Size>(
            { it.width.toLong() * it.height.toLong() },
            { abs(min(it.width, it.height).toFloat() / max(it.width, it.height) - targetPortraitRatio) }
        )
    )

    private fun hasSurfaceEncoder(mime: String, size: Size, fps: Int): Boolean {
        val codecs = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.getOrNull() ?: return false
        return codecs.any { codec ->
            if (!codec.isEncoder || codec.supportedTypes.none { it.equals(mime, true) }) return@any false
            val caps = runCatching { codec.getCapabilitiesForType(mime) }.getOrNull()
                ?: return@any false
            if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) return@any false
            runCatching {
                caps.videoCapabilities.areSizeAndRateSupported(
                    size.width, size.height, fps.toDouble()
                )
            }.getOrDefault(false)
        }
    }
}

class DualCaptureEngine(
    context: Context,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
    private val onRecordingFinished: (android.net.Uri?) -> Unit
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("DualCaptureCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private var support: DualCaptureSupport? = null
    private var option: DualCaptureResolutionOption? = null
    private var gl: DualCaptureGlCompositor? = null
    private var rearDevice: CameraDevice? = null
    private var frontDevice: CameraDevice? = null
    private var rearSession: CameraCaptureSession? = null
    private var frontSession: CameraCaptureSession? = null
    private var rearSurface: Surface? = null
    private var frontSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var previewWidth = 1
    private var previewHeight = 1
    private var openCount = 0
    private var sessionCount = 0
    private var failed = false
    private var closed = false
    @Volatile private var recording = false
    @Volatile private var layout = DualCaptureLayout.PICTURE_IN_PICTURE
    @Volatile private var pipX = 0.62f
    @Volatile private var pipY = 0.68f
    private var recorder: VideoRecorder? = null

    @SuppressLint("MissingPermission")
    fun start(
        preview: Surface,
        width: Int,
        height: Int,
        capabilities: DualCaptureSupport,
        selected: DualCaptureResolutionOption
    ) {
        if (closed) return
        previewSurface = preview
        previewWidth = width.coerceAtLeast(1)
        previewHeight = height.coerceAtLeast(1)
        support = capabilities
        option = selected
        failed = false
        openCount = 0
        sessionCount = 0
        val compositor = DualCaptureGlCompositor()
        gl = compositor
        compositor.initialize(
            preview, previewWidth, previewHeight,
            selected.backInputSize, selected.frontInputSize,
            capabilities.backCamera.sensorOrientation,
            capabilities.frontCamera.sensorOrientation,
            onSurfaces = { rear, front ->
                rearSurface = rear
                frontSurface = front
                cameraHandler.post { openBothCameras() }
            },
            onError = ::fail
        )
        compositor.setLayout(layout)
        compositor.setPipPosition(pipX, pipY)
    }

    fun updatePreview(preview: Surface, width: Int, height: Int) {
        previewSurface = preview
        previewWidth = width.coerceAtLeast(1)
        previewHeight = height.coerceAtLeast(1)
        gl?.setPreview(preview, previewWidth, previewHeight)
    }

    fun detachPreview() {
        previewSurface = null
        gl?.setPreview(null, 0, 0)
    }

    fun setLayout(value: DualCaptureLayout) {
        layout = value
        gl?.setLayout(value)
    }

    fun setPipPosition(x: Float, y: Float) {
        pipX = x.coerceIn(0f, 1f)
        pipY = y.coerceIn(0f, 1f)
        gl?.setPipPosition(pipX, pipY)
    }

    fun changeResolution(selected: DualCaptureResolutionOption) {
        if (closed || recording) return
        val currentSupport = support ?: return
        val surface = previewSurface ?: return
        val w = previewWidth
        val h = previewHeight
        cameraHandler.post {
            closeCameras()
            gl?.release()
            gl = null
            option = selected
            failed = false
            openCount = 0
            sessionCount = 0
            start(surface, w, h, currentSupport, selected)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openBothCameras() {
        if (closed || failed) return
        val current = support ?: return
        try {
            manager.openCamera(current.backCamera.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed || failed) { camera.close(); return }
                    rearDevice = camera
                    openCount++
                    configureWhenBothOpen()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    fail("Rear camera disconnected during dual capture.")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    fail("Rear camera open failed (Camera2 error §error).")
                }
            }, cameraHandler)
            manager.openCamera(current.frontCamera.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed || failed) { camera.close(); return }
                    frontDevice = camera
                    openCount++
                    configureWhenBothOpen()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    fail("Front camera disconnected during dual capture.")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    fail("Front camera open failed (Camera2 error §error).")
                }
            }, cameraHandler)
        } catch (error: Exception) {
            fail("Unable to open both cameras: ${error.localizedMessage ?: error.javaClass.simpleName}")
        }
    }

    private fun configureWhenBothOpen() {
        if (openCount != 2 || failed || closed) return
        createCameraSession(rearDevice ?: return, rearSurface ?: return, true)
        createCameraSession(frontDevice ?: return, frontSurface ?: return, false)
    }

    private fun createCameraSession(device: CameraDevice, surface: Surface, isRear: Boolean) {
        try {
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (closed || failed) { session.close(); return }
                    try {
                        val characteristics = manager.getCameraCharacteristics(device.id)
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            val availableAfModes = characteristics.get(
                                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
                            ) ?: intArrayOf()
                            val afMode = when {
                                availableAfModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                availableAfModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                else -> CaptureRequest.CONTROL_AF_MODE_OFF
                            }
                            set(CaptureRequest.CONTROL_AF_MODE, afMode)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            chooseFpsRange(
                                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES),
                                support?.fps ?: 30
                            )?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        }.build()
                        session.setRepeatingRequest(request, null, cameraHandler)
                        if (isRear) rearSession = session else frontSession = session
                        sessionCount++
                        if (sessionCount == 2) onReady()
                    } catch (error: Exception) {
                        session.close()
                        fail("Could not start dual-camera streams: ${error.localizedMessage}")
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    fail("The camera pair is concurrent, but this two-stream resolution was rejected. Try a lower resolution.")
                }
            }, cameraHandler)
        } catch (error: Exception) {
            fail("Dual-camera session setup failed: ${error.localizedMessage}")
        }
    }

    private fun chooseFpsRange(ranges: Array<Range<Int>>?, fps: Int): Range<Int>? {
        if (ranges.isNullOrEmpty()) return null
        return ranges.firstOrNull { it.lower <= fps && it.upper >= fps }
            ?: ranges.filter { it.upper <= fps }.maxByOrNull { it.upper }
            ?: ranges.minByOrNull { abs(it.upper - fps) }
    }

    fun startRecording(selected: DualCaptureResolutionOption, fps: Int, bitrateMbps: Int, codecMime: String) {
        if (closed || recording || sessionCount != 2) {
            if (sessionCount != 2) onError("Both camera streams are not ready yet.")
            return
        }
        val nextRecorder = VideoRecorder(app)
        recorder = nextRecorder
        val started = nextRecorder.startRecording(
            size = selected.outputSize,
            cameraInputSize = selected.outputSize,
            fps = fps,
            bitrateMbps = bitrateMbps.coerceAtLeast(8),
            codecMime = codecMime,
            cameraTimestampSource = CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            sensorOrientationDegrees = 0,
            orientationHintDegrees = 0,
            recordingPath = VideoRecordingPath.DCIM_PHOTON,
            onError = { message ->
                recording = false
                if (recorder === nextRecorder) recorder = null
                nextRecorder.release()
                onError(message)
            },
            onFinished = { uri ->
                recording = false
                if (recorder === nextRecorder) recorder = null
                nextRecorder.release()
                onRecordingFinished(uri)
            }
        )
        if (!started) {
            nextRecorder.release()
            recorder = null
            onError("Video encoder could not start at ${selected.outputSize.width}×${selected.outputSize.height}.")
            return
        }
        val inputSurface = nextRecorder.cameraInputSurface
        if (inputSurface == null) {
            nextRecorder.stopRecording()
            onError("The video input surface was not created.")
            return
        }
        recording = true
        gl?.setEncoder(inputSurface, selected.outputSize)
        nextRecorder.onCameraInputStarted()
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        gl?.detachEncoder()
        recorder?.stopRecording()
    }

    fun isRecording(): Boolean = recording

    private fun fail(message: String) {
        cameraHandler.post {
            if (failed || closed) return@post
            failed = true
            PLog.e("DualCaptureEngine", message)
            if (recording) stopRecording()
            closeCameras()
            onError(message)
        }
    }

    private fun closeCameras() {
        try { rearSession?.stopRepeating() } catch (_: Exception) {}
        try { frontSession?.stopRepeating() } catch (_: Exception) {}
        try { rearSession?.abortCaptures() } catch (_: Exception) {}
        try { frontSession?.abortCaptures() } catch (_: Exception) {}
        try { rearSession?.close() } catch (_: Exception) {}
        try { frontSession?.close() } catch (_: Exception) {}
        rearSession = null
        frontSession = null
        try { rearDevice?.close() } catch (_: Exception) {}
        try { frontDevice?.close() } catch (_: Exception) {}
        rearDevice = null
        frontDevice = null
        rearSurface = null
        frontSurface = null
        openCount = 0
        sessionCount = 0
    }

    fun close() {
        if (closed) return
        closed = true
        cameraHandler.post {
            if (recording) stopRecording()
            if (!recording) {
                recorder?.release()
                recorder = null
            }
            closeCameras()
            gl?.release()
            gl = null
            cameraThread.quitSafely()
        }
    }
}

private class DualCaptureGlCompositor {
    companion object {
        private const val TAG = "DualCaptureGL"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EGL_OPENGL_ES2_BIT = 4
        private const val PIP_WIDTH = 0.34f
        private const val PIP_HEIGHT = 0.24f
    }

    private val thread = HandlerThread("DualCaptureRender").apply { start() }
    private val handler = Handler(thread.looper)
    private val renderQueued = AtomicBoolean(false)
    private val pending = BooleanArray(2)
    private val hasFrame = BooleanArray(2)
    private val textureIds = IntArray(2)
    private val cameraTextures = arrayOfNulls<SurfaceTexture>(2)
    private val cameraSurfaces = arrayOfNulls<Surface>(2)
    private val inputSizes = arrayOf(Size(1, 1), Size(1, 1))
    private val rotations = intArrayOf(90, 270)
    private val textureMatrices = Array(2) { FloatArray(16).also { Matrix.setIdentityM(it, 0) } }
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSurface: Surface? = null
    private var previewWidth = 1
    private var previewHeight = 1
    private var encoderSize = Size(1, 1)
    private var program = 0
    private var posLoc = -1
    private var texLoc = -1
    private var matrixLoc = -1
    private var samplerLoc = -1
    private var layout = DualCaptureLayout.PICTURE_IN_PICTURE
    private var pipX = 0.62f
    private var pipY = 0.68f
    private var ready = false
    private var released = false
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }
    private val uv: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0)
        }

    fun initialize(
        preview: Surface,
        width: Int,
        height: Int,
        rearSize: Size,
        frontSize: Size,
        rearRotation: Int,
        frontRotation: Int,
        onSurfaces: (Surface, Surface) -> Unit,
        onError: (String) -> Unit
    ) {
        handler.post {
            try {
                previewSurface = preview
                previewWidth = width.coerceAtLeast(1)
                previewHeight = height.coerceAtLeast(1)
                inputSizes[0] = rearSize
                inputSizes[1] = frontSize
                rotations[0] = rearRotation
                rotations[1] = frontRotation
                initEgl()
                createPreviewSurface(preview)
                initProgram()
                initCameraSurfaces()
                ready = true
                onSurfaces(cameraSurfaces[0]!!, cameraSurfaces[1]!!)
            } catch (error: Exception) {
                PLog.e(TAG, "GL setup failed", error)
                onError("Dual-capture preview setup failed: ${error.localizedMessage}")
            }
        }
    }

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL display unavailable" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL initialization failed" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "Recordable EGL config not found"
        }
        config = configs[0]!!
        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed" }
    }

    private fun createPreviewSurface(surface: Surface) {
        if (!surface.isValid) return
        if (previewEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewEgl)
        previewEgl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(previewEgl != EGL14.EGL_NO_SURFACE) { "Preview EGL surface failed" }
    }

    private fun initProgram() {
        val vertex = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val fragment = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
        """.trimIndent()
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        check(status[0] == GLES20.GL_TRUE) { "GL program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        matrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        samplerLoc = GLES20.glGetUniformLocation(program, "uTexture")
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("GL shader compile failed: §log")
        }
        return shader
    }

    private fun initCameraSurfaces() {
        GLES20.glGenTextures(2, textureIds, 0)
        for (index in 0..1) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[index])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val texture = SurfaceTexture(textureIds[index]).apply {
                setDefaultBufferSize(inputSizes[index].width, inputSizes[index].height)
                setOnFrameAvailableListener({
                    pending[index] = true
                    scheduleRender()
                }, handler)
            }
            cameraTextures[index] = texture
            cameraSurfaces[index] = Surface(texture)
        }
    }

    fun setPreview(surface: Surface?, width: Int, height: Int) {
        handler.post {
            previewSurface = surface
            previewWidth = width.coerceAtLeast(1)
            previewHeight = height.coerceAtLeast(1)
            if (!ready || released) return@post
            try {
                if (surface == null || !surface.isValid) {
                    if (previewEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewEgl)
                    previewEgl = EGL14.EGL_NO_SURFACE
                } else {
                    createPreviewSurface(surface)
                    scheduleRender()
                }
            } catch (error: Exception) {
                PLog.e(TAG, "Preview surface update failed", error)
            }
        }
    }

    fun setLayout(value: DualCaptureLayout) {
        handler.post { layout = value; scheduleRender() }
    }

    fun setPipPosition(x: Float, y: Float) {
        handler.post { pipX = x.coerceIn(0f, 1f); pipY = y.coerceIn(0f, 1f); scheduleRender() }
    }

    fun setEncoder(surface: Surface, size: Size) {
        handler.post {
            if (!ready || released) return@post
            try {
                if (encoderEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderEgl)
                encoderSize = size
                encoderEgl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                check(encoderEgl != EGL14.EGL_NO_SURFACE) { "Encoder EGL surface creation failed" }
                scheduleRender()
            } catch (error: Exception) {
                PLog.e(TAG, "Encoder input attachment failed", error)
            }
        }
    }

    fun detachEncoder() {
        handler.post {
            if (encoderEgl != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, encoderEgl)
                encoderEgl = EGL14.EGL_NO_SURFACE
                if (previewEgl != EGL14.EGL_NO_SURFACE) makeCurrent(previewEgl)
            }
        }
    }

    private fun scheduleRender() {
        if (ready && !released && renderQueued.compareAndSet(false, true)) {
            handler.post { renderFrames() }
        }
    }

    private fun renderFrames() {
        try {
            for (i in 0..1) {
                if (pending[i]) {
                    pending[i] = false
                    val texture = cameraTextures[i] ?: continue
                    texture.updateTexImage()
                    texture.getTransformMatrix(textureMatrices[i])
                    hasFrame[i] = true
                }
            }
            if (previewEgl != EGL14.EGL_NO_SURFACE && previewSurface?.isValid == true) {
                makeCurrent(previewEgl)
                drawComposition(previewWidth, previewHeight)
                EGL14.eglSwapBuffers(display, previewEgl)
            }
            if (encoderEgl != EGL14.EGL_NO_SURFACE) {
                makeCurrent(encoderEgl)
                drawComposition(encoderSize.width, encoderSize.height)
                EGLExt.eglPresentationTimeANDROID(display, encoderEgl, SystemClock.elapsedRealtimeNanos())
                EGL14.eglSwapBuffers(display, encoderEgl)
            }
        } catch (error: Exception) {
            PLog.e(TAG, "Frame composition failed", error)
        } finally {
            renderQueued.set(false)
            if (pending.any { it }) scheduleRender()
        }
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    private fun drawComposition(width: Int, height: Int) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        when (layout) {
            DualCaptureLayout.PICTURE_IN_PICTURE -> {
                drawCamera(0, 0, width, height, 0)
                val w = (width * PIP_WIDTH).toInt().coerceAtLeast(1)
                val h = (height * PIP_HEIGHT).toInt().coerceAtLeast(1)
                val x = (pipX * (width - w)).toInt().coerceIn(0, width - w)
                val top = (pipY * (height - h)).toInt().coerceIn(0, height - h)
                val y = height - top - h
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(x, y, w, h)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                drawCamera(x, y, w, h, 1)
            }
            DualCaptureLayout.STACKED -> {
                val half = height / 2
                drawCamera(0, half, width, height - half, 0)
                drawCamera(0, 0, width, half, 1)
            }
        }
    }

    private fun drawCamera(x: Int, y: Int, w: Int, h: Int, index: Int) {
        if (w <= 0 || h <= 0 || textureIds[index] == 0 || !hasFrame[index]) return
        GLES20.glViewport(x, y, w, h)
        GLES20.glUseProgram(program)
        vertices.position(0)
        uv.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, uv)

        val input = inputSizes[index]
        val rotation = ((rotations[index] % 360) + 360) % 360
        val sourceAspect = if (rotation % 180 == 90) {
            input.height.toFloat() / input.width
        } else {
            input.width.toFloat() / input.height
        }
        val targetAspect = w.toFloat() / h
        var cropX = 1f
        var cropY = 1f
        if (sourceAspect > targetAspect) cropX = targetAspect / sourceAspect
        else cropY = sourceAspect / targetAspect
        val matrixCropX = if (rotation % 180 == 90) cropY else cropX
        val matrixCropY = if (rotation % 180 == 90) cropX else cropY

        val extra = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        Matrix.translateM(extra, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(extra, 0, rotation.toFloat(), 0f, 0f, 1f)
        if (index == 1) Matrix.scaleM(extra, 0, -1f, 1f, 1f)
        Matrix.scaleM(extra, 0, matrixCropX, matrixCropY, 1f)
        Matrix.translateM(extra, 0, -0.5f, -0.5f, 0f)
        val finalMatrix = FloatArray(16)
        Matrix.multiplyMM(finalMatrix, 0, textureMatrices[index], 0, extra, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[index])
        GLES20.glUniform1i(samplerLoc, 0)
        GLES20.glUniformMatrix4fv(matrixLoc, 1, false, finalMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    fun release() {
        if (released) return
        handler.post {
            if (released) return@post
            released = true
            try {
                for (i in 0..1) {
                    cameraSurfaces[i]?.release()
                    cameraTextures[i]?.release()
                }
                GLES20.glDeleteTextures(2, textureIds, 0)
                if (program != 0) GLES20.glDeleteProgram(program)
                if (previewEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewEgl)
                if (encoderEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderEgl)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
            } catch (error: Exception) {
                PLog.e(TAG, "Release failed", error)
            } finally {
                thread.quitSafely()
            }
        }
    }
}
