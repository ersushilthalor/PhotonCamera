package com.hinnka.mycamera.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.hinnka.mycamera.utils.PLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Two-input Camera2 relay for uninterrupted rear-lens recording.
 *
 * Camera2 writes the main and ultra-wide streams to separate OES surfaces. This relay keeps both
 * inputs drained and renders the selected source to RealtimeVideoRenderer's existing input
 * Surface. The downstream Photon colour, LUT, log, audio, and muxer pipeline remains unchanged.
 */
internal class DualCameraRecordingRelay(
    private val downstreamSurface: Surface,
    width: Int,
    height: Int,
    private val fps: Int,
    initialUltraWide: Boolean,
) {
    companion object {
        private const val TAG = "DualCameraRecordingRelay"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val MAX_HANDOFF_FRAMES = 4
        private const val HANDOFF_TIMEOUT_MS = 900L
        private const val EGL_TIMEOUT_MS = 2_000L
    }

    private val frameWidth = max(width, 320)
    private val frameHeight = max(height, 240)
    private val released = AtomicBoolean(false)
    private val thread = HandlerThread("PhotonDualCameraRelayGL").apply { start() }
    private val handler = Handler(thread.looper)
    private val initLatch = CountDownLatch(1)

    @Volatile var isReady: Boolean = false
        private set

    @Volatile private var activeUltraWide = initialUltraWide
    @Volatile private var pendingUltraWide: Boolean? = null
    @Volatile private var handoffFrame = 0
    @Volatile private var lastHandoffInputTimestampNs = 0L
    @Volatile private var lastMainTimestampNs = 0L
    @Volatile private var lastUltraTimestampNs = 0L
    @Volatile private var mainFrames = 0L
    @Volatile private var ultraFrames = 0L
    @Volatile private var lastOutputTimestampNs = 0L
    @Volatile private var pendingSwitchDeadlineMs = 0L

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var mainTextureId = 0
    private var ultraTextureId = 0
    private var programId = 0
    private var mainSt: SurfaceTexture? = null
    private var ultraSt: SurfaceTexture? = null
    private var mainSurface: Surface? = null
    private var ultraSurface: Surface? = null
    private var mainMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var ultraMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var vertexBuffer: FloatBuffer? = null
    private var texBuffer: FloatBuffer? = null
    private var tickPosted = false

    val mainInputSurface: Surface?
        get() = mainSurface?.takeIf { isReady && it.isValid }

    val ultraWideInputSurface: Surface?
        get() = ultraSurface?.takeIf { isReady && it.isValid }

    private val frameTick = object : Runnable {
        override fun run() {
            tickPosted = false
            if (released.get() || !isReady) return
            renderCurrentFrame()
            scheduleNextTick()
        }
    }

    init {
        handler.post {
            try {
                setupEgl()
                isReady = true
                scheduleNextTick()
            } catch (error: Throwable) {
                PLog.e(TAG, "Dual input relay initialisation failed", error)
                releaseGlResources()
            } finally {
                initLatch.countDown()
            }
        }
        try {
            if (!initLatch.await(EGL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                PLog.w(TAG, "Timed out creating dual input relay")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun hasFrames(isUltraWide: Boolean): Boolean =
        if (isUltraWide) ultraFrames >= 2L else mainFrames >= 2L

    fun setActiveSource(isUltraWide: Boolean) {
        if (released.get()) return
        handler.post {
            if (released.get()) return@post
            val current = pendingUltraWide ?: activeUltraWide
            if (current == isUltraWide) return@post
            pendingUltraWide = isUltraWide
            handoffFrame = 0
            lastHandoffInputTimestampNs = if (isUltraWide) lastUltraTimestampNs else lastMainTimestampNs
            pendingSwitchDeadlineMs = SystemClock.elapsedRealtime() + HANDOFF_TIMEOUT_MS
            PLog.i(TAG, "Waiting for distinct incoming frames from ${if (isUltraWide) "ultra-wide" else "main"}")
            scheduleNextTick()
        }
    }

    fun updateBufferSize(isUltraWide: Boolean, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || released.get()) return
        if (Thread.currentThread() === thread) {
            val target = if (isUltraWide) ultraSt else mainSt
            runCatching { target?.setDefaultBufferSize(width, height) }
                .onFailure { PLog.w(TAG, "Unable to update relay input size: ${it.message}") }
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try {
                val target = if (isUltraWide) ultraSt else mainSt
                target?.setDefaultBufferSize(width, height)
            } catch (error: Exception) {
                PLog.w(TAG, "Unable to update relay input size: ${error.message}")
            } finally {
                latch.countDown()
            }
        }
        try {
            if (!latch.await(750L, TimeUnit.MILLISECONDS)) {
                PLog.w(TAG, "Timed out updating relay input buffer size: ${width}x$height")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun pause() {
        handler.post { handler.removeCallbacks(frameTick); tickPosted = false }
    }

    fun resume() {
        handler.post { scheduleNextTick() }
    }

    private fun setupEgl() {
        check(downstreamSurface.isValid) { "Downstream renderer input Surface is invalid" }
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        display = eglDisplay

        val configAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No recordable EGL config available"
        }
        val config = requireNotNull(configs[0])
        val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttrs, 0)
        check(eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        context = eglContext

        val window = EGL14.eglCreateWindowSurface(
            display, config, downstreamSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(window != null && window != EGL14.EGL_NO_SURFACE) { "Cannot create relay output EGL surface" }
        eglSurface = window
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }

        programId = createProgram(
            """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uMainMatrix;
            uniform mat4 uUltraMatrix;
            varying vec2 vMainCoord;
            varying vec2 vUltraCoord;
            void main() {
                gl_Position = aPosition;
                vMainCoord = (uMainMatrix * aTexCoord).xy;
                vUltraCoord = (uUltraMatrix * aTexCoord).xy;
            }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sMain;
            uniform samplerExternalOES sUltra;
            uniform float uMix;
            varying vec2 vMainCoord;
            varying vec2 vUltraCoord;
            void main() {
                vec4 mainColor = texture2D(sMain, vMainCoord);
                vec4 ultraColor = texture2D(sUltra, vUltraCoord);
                gl_FragColor = mix(mainColor, ultraColor, uMix);
            }
            """.trimIndent(),
        )

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        mainTextureId = textures[0]
        ultraTextureId = textures[1]
        textures.forEach { texture ->
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        vertexBuffer = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f))
            position(0)
        }
        texBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            position(0)
        }

        mainSt = SurfaceTexture(mainTextureId).apply {
            setDefaultBufferSize(frameWidth, frameHeight)
            setOnFrameAvailableListener({ updateInputFrame(false) }, handler)
        }
        ultraSt = SurfaceTexture(ultraTextureId).apply {
            setDefaultBufferSize(frameWidth, frameHeight)
            setOnFrameAvailableListener({ updateInputFrame(true) }, handler)
        }
        mainSurface = Surface(requireNotNull(mainSt))
        ultraSurface = Surface(requireNotNull(ultraSt))
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        PLog.i(TAG, "Dual recording relay ready: ${frameWidth}x$frameHeight @ ${fps}fps")
    }

    private fun updateInputFrame(isUltraWide: Boolean) {
        if (released.get() || !isReady) return
        val st = if (isUltraWide) ultraSt else mainSt
        try {
            st?.updateTexImage()
            val timestamp = st?.timestamp ?: 0L
            if (timestamp > 0L) {
                if (isUltraWide) {
                    st?.getTransformMatrix(ultraMatrix)
                    lastUltraTimestampNs = timestamp
                    ultraFrames += 1L
                } else {
                    st?.getTransformMatrix(mainMatrix)
                    lastMainTimestampNs = timestamp
                    mainFrames += 1L
                }
            }
            // Input streams are only latched here. The fixed-FPS output tick owns presentation,
            // so two concurrent camera callbacks cannot accidentally drive a 2x/3x encoder rate.
            scheduleNextTick()
        } catch (error: Throwable) {
            PLog.w(TAG, "Could not update ${if (isUltraWide) "ultra-wide" else "main"} relay texture: ${error.message}")
        }
    }

    private fun renderCurrentFrame() {
        if (released.get() || !isReady || display == EGL14.EGL_NO_DISPLAY ||
            eglSurface == EGL14.EGL_NO_SURFACE || context == EGL14.EGL_NO_CONTEXT
        ) return

        val pending = pendingUltraWide
        if (pending != null) {
            val targetTimestamp = if (pending) lastUltraTimestampNs else lastMainTimestampNs
            val targetReady = targetTimestamp > 0L
            if (targetReady && targetTimestamp > lastHandoffInputTimestampNs) {
                lastHandoffInputTimestampNs = targetTimestamp
                handoffFrame += 1
                if (handoffFrame >= MAX_HANDOFF_FRAMES) {
                    activeUltraWide = pending
                    pendingUltraWide = null
                    handoffFrame = 0
                    PLog.i(TAG, "Recording stream handoff completed: ultraWide=$activeUltraWide")
                }
            } else if (SystemClock.elapsedRealtime() >= pendingSwitchDeadlineMs) {
                PLog.w(TAG, "Recording stream handoff timed out; retaining current source")
                pendingUltraWide = null
                handoffFrame = 0
            }
        }

        val activeHasFrame = if (activeUltraWide) lastUltraTimestampNs > 0L else lastMainTimestampNs > 0L
        if (!activeHasFrame && pendingUltraWide == null) return
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) return

        val mix = when {
            pendingUltraWide == null -> if (activeUltraWide) 1f else 0f
            handoffFrame <= 0 -> if (activeUltraWide) 1f else 0f
            pendingUltraWide == true -> handoffFrame.toFloat() / MAX_HANDOFF_FRAMES
            else -> 1f - handoffFrame.toFloat() / MAX_HANDOFF_FRAMES
        }.coerceIn(0f, 1f)

        GLES20.glViewport(0, 0, frameWidth, frameHeight)
        GLES20.glUseProgram(programId)
        val positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
        val mainMatrixHandle = GLES20.glGetUniformLocation(programId, "uMainMatrix")
        val ultraMatrixHandle = GLES20.glGetUniformLocation(programId, "uUltraMatrix")
        val mixHandle = GLES20.glGetUniformLocation(programId, "uMix")
        val mainSamplerHandle = GLES20.glGetUniformLocation(programId, "sMain")
        val ultraSamplerHandle = GLES20.glGetUniformLocation(programId, "sUltra")

        GLES20.glUniformMatrix4fv(mainMatrixHandle, 1, false, mainMatrix, 0)
        GLES20.glUniformMatrix4fv(ultraMatrixHandle, 1, false, ultraMatrix, 0)
        GLES20.glUniform1f(mixHandle, mix)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mainTextureId)
        GLES20.glUniform1i(mainSamplerHandle, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ultraTextureId)
        GLES20.glUniform1i(ultraSamplerHandle, 1)

        val vertices = vertexBuffer ?: return
        val coordinates = texBuffer ?: return
        vertices.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(positionHandle)
        coordinates.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        val nowNs = SystemClock.elapsedRealtimeNanos()
        val intervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
        val ptsNs = max(nowNs, lastOutputTimestampNs + intervalNs.coerceAtLeast(1L))
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
        if (EGL14.eglSwapBuffers(display, eglSurface)) {
            lastOutputTimestampNs = ptsNs
        } else {
            PLog.w(TAG, "Relay eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    private fun scheduleNextTick() {
        if (tickPosted || released.get() || !isReady) return
        tickPosted = true
        handler.postDelayed(frameTick, (1_000L / fps.coerceAtLeast(1)).coerceAtLeast(1L))
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(status[0] == GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            "Relay shader link failed: $info"
        }
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Relay shader compile failed: $info")
        }
        return shader
    }

    private fun releaseGlResources() {
        runCatching { mainSt?.setOnFrameAvailableListener(null) }
        runCatching { ultraSt?.setOnFrameAvailableListener(null) }
        runCatching { mainSurface?.release() }
        runCatching { ultraSurface?.release() }
        mainSurface = null
        ultraSurface = null
        runCatching { mainSt?.release() }
        runCatching { ultraSt?.release() }
        mainSt = null
        ultraSt = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT) runCatching {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (programId != 0) runCatching { GLES20.glDeleteProgram(programId) }
            val textures = intArrayOf(mainTextureId, ultraTextureId).filter { it != 0 }.toIntArray()
            if (textures.isNotEmpty()) runCatching { GLES20.glDeleteTextures(textures.size, textures, 0) }
            if (eglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, eglSurface) }
            if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        programId = 0
        mainTextureId = 0
        ultraTextureId = 0
        isReady = false
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        val latch = CountDownLatch(1)
        handler.post {
            try {
                handler.removeCallbacks(frameTick)
                releaseGlResources()
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(1_500L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread.quitSafely()
    }
}
