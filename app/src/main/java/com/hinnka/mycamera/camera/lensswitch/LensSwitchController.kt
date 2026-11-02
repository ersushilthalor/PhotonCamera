package com.hinnka.mycamera.camera.lensswitch

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Standalone warm-standby Camera2 lens switch coordinator.
 *
 * This class is deliberately isolated from the existing capture engine. It expects one
 * independently-owned preview Surface per Lens. PhotonCamera's current preview is driven
 * by a single GLSurfaceView/LutRenderer SurfaceTexture, so this coordinator must not be
 * activated until the GL renderer can provide and consume per-lens input surfaces and the
 * capture engine can share/transfer CameraDevice ownership safely.
 *
 * UI contract when that adapter exists:
 * - setSurface(lens, Surface) when a per-lens GL input surface becomes available.
 * - onFrame(lens) only after that input has actually delivered a frame.
 * - onShowStream(lens) selects the matching input in the GL renderer.
 * - onFreeze(show) controls the existing frozen-preview overlay.
 * - onResume/onPause follow the owning camera lifecycle.
 *
 * The controller chooses warm mode conservatively:
 * - LOGICAL: two physical output surfaces on one logical CameraDevice.
 * - CONCURRENT: two CameraDevices only when the platform explicitly reports the pair.
 * - COLD: all other pairs; no standby device is opened.
 */
class LensSwitchController(
    context: Context,
    private val callbacks: Callbacks,
    private val lowPowerStandby: Boolean = true,
) {
    data class Lens(val cameraId: String, val physicalId: String? = null) {
        val key: String get() = "$cameraId/${physicalId ?: "-"}"
    }

    enum class Status { IDLE, OPENING, READY_INSTANT, READY_COLD, STANDBY_UNAVAILABLE, ERROR }

    interface Callbacks {
        fun onShowStream(lens: Lens)
        fun onFreeze(show: Boolean)
        fun onStatus(status: Status)
    }

    private enum class Mode { COLD, LOGICAL, CONCURRENT }

    private class Rig(val lenses: List<Lens>) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var closed = false

        fun close() {
            closed = true
            try { session?.close() } catch (_: Throwable) {}
            try { device?.close() } catch (_: Throwable) {}
            session = null
            device = null
        }
    }

    private val app = context.applicationContext
    private val mgr = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val prefs = app.getSharedPreferences("lens_switch", Context.MODE_PRIVATE)
    private val thread = HandlerThread("LensSwitch").apply { start() }
    private val handler = Handler(thread.looper)
    private val exec = Executor { handler.post(it) }
    private val main = Handler(Looper.getMainLooper())

    private val surfaces = ConcurrentHashMap<String, Surface>()
    private val framed = ConcurrentHashMap<String, Boolean>()
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    // All state below is confined to handler's thread.
    private var wantRunning = false
    private var desired: Lens? = null
    private var standbyWanted: Lens? = null
    private var activeZoom = 1f
    private var standbyZoom = 1f
    private val rigs = ArrayList<Rig>()
    private var opening = false
    private var mode = Mode.COLD
    private var shown: Lens? = null
    private var instantSwitch = false
    private var attempts = 0
    private var watchdogToken = 0
    private var standbyOpenedAt = 0L
    private var standbyRetryAt = 0L
    private var standbyFailures = 0

    fun start(initial: Lens, standby: Lens?) {
        handler.post {
            desired = initial
            standbyWanted = standby
            reconcile()
        }
    }

    fun switchTo(lens: Lens) {
        handler.post {
            val prev = desired
            if (prev == lens) return@post
            instantSwitch = framed.containsKey(lens.key) &&
                rigs.any { lens in it.lenses && it.session != null }
            desired = lens
            if (standbyWanted == lens || standbyWanted == null) standbyWanted = prev
            attempts = 0
            armWatchdog()
            reconcile()
        }
    }

    fun setZoom(active: Float, standby: Float = 1f) {
        handler.post {
            activeZoom = active
            standbyZoom = standby
            applyRequests()
        }
    }

    fun setSurface(lens: Lens, surface: Surface?) {
        if (surface != null) surfaces[lens.key] = surface else surfaces.remove(lens.key)
        handler.post {
            if (surface == null) {
                rigs.filter { lens in it.lenses }.toList().forEach { dropRig(it) }
                opening = rigs.any { it.session == null }
            }
            reconcile()
        }
    }

    fun onFrame(lens: Lens) {
        if (SystemClock.uptimeMillis() < (blockedUntil[lens.key] ?: 0L)) return
        if (framed.putIfAbsent(lens.key, true) == null) {
            handler.post {
                attempts = 0
                reconcile()
            }
        }
    }

    fun onResume() {
        handler.post {
            wantRunning = true
            attempts = 0
            reconcile()
        }
    }

    /** Call from the lifecycle owner; blocking mode is intended for the main thread only. */
    fun onPause(blocking: Boolean = false) {
        val latch = if (blocking) CountDownLatch(1) else null
        handler.post {
            wantRunning = false
            teardownAll()
            latch?.countDown()
        }
        main.post {
            callbacks.onFreeze(false)
            callbacks.onStatus(Status.IDLE)
        }
        latch?.await(1500, TimeUnit.MILLISECONDS)
    }

    fun release() {
        handler.post {
            wantRunning = false
            teardownAll()
            thread.quitSafely()
        }
    }

    private fun reconcile() {
        if (!wantRunning) return
        val want = desired ?: return
        val rig = rigs.firstOrNull { want in it.lenses }
        if (rig == null) {
            rigs.filter { it.session == null }.toList().forEach { dropRig(it) }
            opening = false
            coldOpen(want)
            return
        }
        if (rig.session == null) return
        if (!framed.containsKey(want.key)) return

        if (shown != want) {
            shown = want
            watchdogToken++
            attempts = 0
            applyRequests()
            main.post {
                callbacks.onShowStream(want)
                callbacks.onFreeze(false)
            }
            emitStatus(if (instantSwitch) Status.READY_INSTANT else Status.READY_COLD)
            instantSwitch = false
        }
        pruneRigs()

        // The standby Surface can become available after the active stream is already
        // running. Re-evaluate the pair at that point instead of leaving the rig stuck
        // permanently in COLD mode from the first surface callback.
        val standby = standbyWanted
        if (mode == Mode.COLD && standby != null && standby != want &&
            surfaces[standby.key]?.isValid == true
        ) {
            when (decideMode(want, standby)) {
                Mode.LOGICAL -> {
                    coldOpen(want) // Recreate this one logical session with both physical outputs.
                    return
                }
                Mode.CONCURRENT -> mode = Mode.CONCURRENT
                Mode.COLD -> Unit
            }
        }
        warmStandby()
    }

    private fun coldOpen(want: Lens) {
        if (opening) return
        if (shown != null) main.post { callbacks.onFreeze(true) }
        rigs.toList().forEach { dropRig(it) }
        shown = null

        val sb = standbyWanted
        mode = if (sb != null && surfaces[sb.key]?.isValid == true) decideMode(want, sb) else Mode.COLD
        val lenses = if (mode == Mode.LOGICAL) listOf(want, sb!!) else listOf(want)
        emitStatus(Status.OPENING)
        openRig(lenses)
        armWatchdog()
    }

    private fun warmStandby() {
        if (mode != Mode.CONCURRENT || opening) return
        val sb = standbyWanted ?: return
        if (rigs.any { sb in it.lenses }) return
        if (surfaces[sb.key]?.isValid != true) return
        val now = SystemClock.uptimeMillis()
        if (now < standbyRetryAt) return
        standbyOpenedAt = now
        openRig(listOf(sb))
    }

    private fun pruneRigs() {
        val keep = listOfNotNull(desired, standbyWanted)
        rigs.filter { rig -> rig.lenses.none { it in keep } }.toList().forEach { dropRig(it) }
    }

    private fun pairKey(a: Lens, b: Lens) = listOf(a.key, b.key).sorted().joinToString("|")

    private fun decideMode(a: Lens, b: Lens): Mode {
        if (a == b) return Mode.COLD
        if (prefs.getBoolean("no_standby_${pairKey(a, b)}", false)) return Mode.COLD

        if (a.cameraId == b.cameraId) {
            val physicalIds = try {
                mgr.getCameraCharacteristics(a.cameraId).physicalCameraIds
            } catch (_: Throwable) {
                emptySet<String>()
            }
            val supported = a.physicalId != null && b.physicalId != null &&
                physicalIds.containsAll(listOf(a.physicalId, b.physicalId))
            return if (supported) Mode.LOGICAL else Mode.COLD
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val supported = try {
                mgr.concurrentCameraIds.any { it.contains(a.cameraId) && it.contains(b.cameraId) }
            } catch (_: Throwable) {
                false
            }
            if (supported) return Mode.CONCURRENT
        }
        return Mode.COLD
    }

    private fun markNoStandby() {
        val d = desired
        val s = standbyWanted
        if (d != null && s != null) {
            prefs.edit().putBoolean("no_standby_${pairKey(d, s)}", true).apply()
        }
        mode = Mode.COLD
        emitStatus(Status.STANDBY_UNAVAILABLE)
    }

    @SuppressLint("MissingPermission")
    private fun openRig(lenses: List<Lens>) {
        if (!lenses.all { surfaces[it.key]?.isValid == true }) return
        val rig = Rig(lenses)
        rigs.add(rig)
        opening = true
        try {
            mgr.openCamera(lenses[0].cameraId, exec, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (rig.closed || !wantRunning) {
                        device.close()
                        return
                    }
                    rig.device = device
                    createSession(rig)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    onRigFailed(rig, "disconnected", -1)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    onRigFailed(rig, "error", error)
                }
            })
        } catch (error: Throwable) {
            onRigFailed(rig, "openCamera threw: $error", -2)
        }
    }

    private fun createSession(rig: Rig) {
        val device = rig.device ?: return
        try {
            val outputs = rig.lenses.map { lens ->
                OutputConfiguration(surfaces.getValue(lens.key)).apply {
                    val physicalId = lens.physicalId
                    if (physicalId != null && physicalId != lens.cameraId) {
                        setPhysicalCameraId(physicalId)
                    }
                }
            }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                exec,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (rig.closed) {
                            session.close()
                            return
                        }
                        rig.session = session
                        try {
                            session.setRepeatingRequest(buildRequest(rig), null, handler)
                        } catch (error: Throwable) {
                            onRigFailed(rig, "repeating request: $error", -3)
                            return
                        }
                        opening = rigs.any { it.session == null }
                        reconcile()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onRigFailed(rig, "configure failed", CONFIGURE_FAILED)
                    }
                }
            )
            device.createCaptureSession(config)
        } catch (error: Throwable) {
            onRigFailed(rig, "createCaptureSession threw: $error", -5)
        }
    }

    private fun buildRequest(rig: Rig): CaptureRequest {
        val device = checkNotNull(rig.device)
        val isActive = desired in rig.lenses
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        rig.lenses.forEach { lens -> builder.addTarget(surfaces.getValue(lens.key)) }
        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = try {
                mgr.getCameraCharacteristics(rig.lenses[0].cameraId)
                    .get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } catch (_: Throwable) {
                null
            }
            val requestedZoom = if (isActive) activeZoom else standbyZoom
            builder.set(
                CaptureRequest.CONTROL_ZOOM_RATIO,
                range?.let { requestedZoom.coerceIn(it.lower, it.upper) } ?: requestedZoom
            )
        }

        if (lowPowerStandby && !isActive) {
            lowFps(rig.lenses[0].cameraId)?.let {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            }
        }
        return builder.build()
    }

    private fun lowFps(cameraId: String): Range<Int>? = try {
        mgr.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper >= 15 }
            ?.minWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))
    } catch (_: Throwable) {
        null
    }

    private fun applyRequests() {
        for (rig in rigs) {
            val session = rig.session ?: continue
            try {
                session.setRepeatingRequest(buildRequest(rig), null, handler)
            } catch (error: Throwable) {
                Log.w(TAG, "applyRequests failed for ${rig.lenses.map { it.key }}", error)
            }
        }
    }

    private fun onRigFailed(rig: Rig, why: String, code: Int) {
        if (rig.closed) return
        Log.w(TAG, "rig ${rig.lenses.map { it.key }} failed: $why ($code)")
        val wasActive = desired in rig.lenses
        dropRig(rig)
        opening = rigs.any { it.session == null }
        val now = SystemClock.uptimeMillis()

        if (wasActive) {
            if (mode == Mode.CONCURRENT && standbyOpenedAt != 0L && now - standbyOpenedAt < 3000L) {
                markNoStandby()
                rigs.toList().forEach { dropRig(it) }
                opening = false
            }
            attempts++
            if (attempts <= MAX_ATTEMPTS) {
                handler.postDelayed({ reconcile() }, 250L * attempts)
            } else {
                emitStatus(Status.ERROR)
                main.post { callbacks.onFreeze(false) }
            }
        } else {
            standbyFailures++
            if (code == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ||
                code == CONFIGURE_FAILED || standbyFailures >= 2
            ) {
                markNoStandby()
            } else {
                standbyRetryAt = now + 1000L
                handler.postDelayed({ reconcile() }, 1000L)
            }
        }
    }

    private fun armWatchdog() {
        val token = ++watchdogToken
        handler.postDelayed({
            if (token != watchdogToken || !wantRunning) return@postDelayed
            val want = desired ?: return@postDelayed
            if (shown == want) return@postDelayed
            Log.w(TAG, "watchdog: no frame for ${want.key}, restarting")
            rigs.toList().forEach { dropRig(it) }
            opening = false
            attempts++
            if (attempts <= MAX_ATTEMPTS) {
                reconcile()
            } else {
                emitStatus(Status.ERROR)
                main.post { callbacks.onFreeze(false) }
            }
        }, WATCHDOG_MS)
    }

    private fun dropRig(rig: Rig) {
        rig.close()
        rigs.remove(rig)
        val until = SystemClock.uptimeMillis() + 150L
        rig.lenses.forEach { lens ->
            framed.remove(lens.key)
            blockedUntil[lens.key] = until
        }
        if (shown != null && shown in rig.lenses) shown = null
    }

    private fun teardownAll() {
        watchdogToken++
        rigs.toList().forEach { dropRig(it) }
        opening = false
        shown = null
    }

    private fun emitStatus(status: Status) {
        main.post { callbacks.onStatus(status) }
    }

    private companion object {
        const val TAG = "LensSwitch"
        const val MAX_ATTEMPTS = 3
        const val WATCHDOG_MS = 2500L
        const val CONFIGURE_FAILED = -4
    }
}
