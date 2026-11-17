package com.hinnka.mycamera.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PhotonPerformanceMode {
    AUTO,
    PERFORMANCE,
    QUALITY,
}

data class PhotonPerformanceSnapshot(
    val mode: PhotonPerformanceMode = PhotonPerformanceMode.AUTO,
    val isRecording: Boolean = false,
    val cpuUsagePercent: Float = 0f,
    val appMemoryPssMb: Int = 0,
    val availableSystemRamMb: Int = 0,
    val lowMemory: Boolean = false,
    val thermalStatus: Int? = null,
    val inputFps: Float = 0f,
    val renderedFps: Float = 0f,
    val droppedFrames: Long = 0L,
    val averageRenderMs: Float = 0f,
    val performanceHintActive: Boolean = false,
)

/**
 * Recording-scoped performance feedback for the realtime camera pipeline.
 *
 * All frame callbacks are delivered on VideoRecordingGL. The monitor does bounded,
 * once-per-second process/thermal sampling and reports actual frame work to Android's
 * PerformanceHintManager when the platform supports it.
 */
object PhotonPerformanceManager {
    private const val SAMPLE_INTERVAL_MS = 2_000L
    private const val NANOS_PER_SECOND = 1_000_000_000L

    private val lock = Any()
    private val mutableSnapshot = MutableStateFlow(PhotonPerformanceSnapshot())
    val snapshot: StateFlow<PhotonPerformanceSnapshot> = mutableSnapshot.asStateFlow()

    @Volatile
    private var activeMode: PhotonPerformanceMode = PhotonPerformanceMode.AUTO

    private var recording = false
    private var targetFps = 30
    private var targetFrameDurationNs = NANOS_PER_SECOND / 30L
    // Keep API-31 types isolated so Android 11 can load this manager safely.
    private var hintSession: Any? = null
    private var powerManager: PowerManager? = null
    private var activityManager: ActivityManager? = null
    private var lastSampleElapsedMs = 0L
    private var lastProcessCpuTimeMs = 0L
    private var inputFramesInWindow = 0
    private var renderedFramesInWindow = 0
    private var droppedFramesTotal = 0L
    private var renderTimeTotalNs = 0L
    private var averageRenderMs = 0f

    fun setMode(mode: PhotonPerformanceMode) {
        synchronized(lock) {
            activeMode = mode
            updateHintTargetLocked()
            mutableSnapshot.value = mutableSnapshot.value.copy(mode = mode)
        }
    }

    /** Must be called from the dedicated recording GL thread so its TID is hinted. */
    fun beginRecording(context: Context, targetFps: Int, mode: PhotonPerformanceMode) {
        synchronized(lock) {
            closeHintSessionLocked()
            activeMode = mode
            this.targetFps = targetFps.coerceIn(1, 240)
            targetFrameDurationNs = (NANOS_PER_SECOND / this.targetFps).coerceAtLeast(1L)
            recording = true
            powerManager = context.getSystemService(PowerManager::class.java)
            activityManager = context.getSystemService(ActivityManager::class.java)
            lastSampleElapsedMs = SystemClock.elapsedRealtime()
            lastProcessCpuTimeMs = Process.getElapsedCpuTime()
            inputFramesInWindow = 0
            renderedFramesInWindow = 0
            droppedFramesTotal = 0L
            renderTimeTotalNs = 0L
            averageRenderMs = 0f

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                hintSession = runCatching {
                    Api31.createHintSession(
                        context = context,
                        threadId = Process.myTid(),
                        targetDurationNs = resolveTargetWorkDurationLocked(),
                    )
                }.getOrNull()
            }
            val thermal = currentThermalStatusLocked()
            mutableSnapshot.value = PhotonPerformanceSnapshot(
                mode = mode,
                isRecording = true,
                thermalStatus = thermal,
                performanceHintActive = hintSession != null,
            )
        }
    }

    fun recordInputFrame() {
        synchronized(lock) {
            if (!recording) return
            inputFramesInWindow += 1
        }
    }

    /** Called after a successfully submitted EGL frame, on the same thread as the hint session. */
    fun recordRenderedFrame(actualWorkDurationNs: Long) {
        synchronized(lock) {
            if (!recording) return
            val durationNs = actualWorkDurationNs.coerceAtLeast(1L)
            hintSession?.let { session ->
                runCatching { Api31.reportActualWorkDuration(session, durationNs) }
            }
            renderedFramesInWindow += 1
            renderTimeTotalNs += durationNs
            publishSampleIfDueLocked()
        }
    }

    fun recordDroppedFrame() {
        synchronized(lock) {
            if (!recording) return
            droppedFramesTotal += 1L
            mutableSnapshot.value = mutableSnapshot.value.copy(droppedFrames = droppedFramesTotal)
        }
    }

    /**
     * Keeps YUV leases bounded under overload. Auto narrows the queue when the device is
     * warm or rendering approaches the frame budget; Quality retains a deeper safety buffer.
     */
    fun maxPendingYuvFrames(): Int = synchronized(lock) {
        when (activeMode) {
            PhotonPerformanceMode.PERFORMANCE -> 2
            PhotonPerformanceMode.QUALITY -> 8
            PhotonPerformanceMode.AUTO -> {
                val thermal = mutableSnapshot.value.thermalStatus
                val thermallyConstrained = thermal != null &&
                    thermal >= PowerManager.THERMAL_STATUS_MODERATE
                val memory = mutableSnapshot.value
                val memoryConstrained = memory.lowMemory ||
                    (memory.availableSystemRamMb in 1..512)
                val renderConstrained = averageRenderMs >=
                    targetFrameDurationNs / 1_000_000f * 0.90f
                if (thermallyConstrained || memoryConstrained || renderConstrained) 2 else 4
            }
        }
    }

    fun stopRecording() {
        synchronized(lock) {
            recording = false
            closeHintSessionLocked()
            mutableSnapshot.value = mutableSnapshot.value.copy(
                mode = activeMode,
                isRecording = false,
                performanceHintActive = false,
                droppedFrames = droppedFramesTotal,
            )
            powerManager = null
            activityManager = null
        }
    }

    private fun publishSampleIfDueLocked() {
        val nowMs = SystemClock.elapsedRealtime()
        val elapsedMs = nowMs - lastSampleElapsedMs
        if (elapsedMs < SAMPLE_INTERVAL_MS) return

        val processCpuNowMs = Process.getElapsedCpuTime()
        val cpuDeltaMs = (processCpuNowMs - lastProcessCpuTimeMs).coerceAtLeast(0L)
        val cpuCapacityPercent = Runtime.getRuntime().availableProcessors().coerceAtLeast(1) * 100f
        val cpuPercent = (cpuDeltaMs * 100f / elapsedMs.toFloat())
            .coerceIn(0f, cpuCapacityPercent)
        val inputFps = inputFramesInWindow * 1_000f / elapsedMs.toFloat()
        val renderedFps = renderedFramesInWindow * 1_000f / elapsedMs.toFloat()
        averageRenderMs = if (renderedFramesInWindow > 0) {
            renderTimeTotalNs / renderedFramesInWindow / 1_000_000f
        } else {
            0f
        }
        val pssMb = runCatching { (Debug.getPss() / 1_024f).toInt() }.getOrDefault(0)
        val memoryInfo = ActivityManager.MemoryInfo()
        val hasMemoryInfo = activityManager?.let { manager ->
            runCatching { manager.getMemoryInfo(memoryInfo); true }.getOrDefault(false)
        } ?: false
        val availableRamMb = if (hasMemoryInfo) {
            (memoryInfo.availMem / (1_024L * 1_024L)).toInt()
        } else {
            0
        }
        val lowMemory = hasMemoryInfo && memoryInfo.lowMemory
        val thermal = currentThermalStatusLocked()

        mutableSnapshot.value = PhotonPerformanceSnapshot(
            mode = activeMode,
            isRecording = recording,
            cpuUsagePercent = cpuPercent,
            appMemoryPssMb = pssMb,
            availableSystemRamMb = availableRamMb,
            lowMemory = lowMemory,
            thermalStatus = thermal,
            inputFps = inputFps,
            renderedFps = renderedFps,
            droppedFrames = droppedFramesTotal,
            averageRenderMs = averageRenderMs,
            performanceHintActive = hintSession != null,
        )

        inputFramesInWindow = 0
        renderedFramesInWindow = 0
        renderTimeTotalNs = 0L
        lastProcessCpuTimeMs = processCpuNowMs
        lastSampleElapsedMs = nowMs
        updateHintTargetLocked()
    }

    private fun currentThermalStatusLocked(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { powerManager?.currentThermalStatus }.getOrNull()
    }

    private fun resolveTargetWorkDurationLocked(): Long {
        val multiplier = when (activeMode) {
            PhotonPerformanceMode.PERFORMANCE -> 0.90f
            PhotonPerformanceMode.AUTO -> {
                val thermal = currentThermalStatusLocked()
                if (thermal != null && thermal >= PowerManager.THERMAL_STATUS_MODERATE) 1.05f else 0.96f
            }
            PhotonPerformanceMode.QUALITY -> 1.00f
        }
        return (targetFrameDurationNs * multiplier).toLong().coerceAtLeast(1L)
    }

    private fun updateHintTargetLocked() {
        val session = hintSession ?: return
        runCatching {
            Api31.updateTargetWorkDuration(session, resolveTargetWorkDurationLocked())
        }.onFailure { closeHintSessionLocked() }
    }

    private fun closeHintSessionLocked() {
        hintSession?.let { session ->
            runCatching { Api31.close(session) }
        }
        hintSession = null
    }

    /** API 31+ calls live here to keep the outer singleton loadable on API 30. */
    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31 {
        fun createHintSession(context: Context, threadId: Int, targetDurationNs: Long): Any? {
            val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return null
            return manager.createHintSession(intArrayOf(threadId), targetDurationNs)
        }

        fun reportActualWorkDuration(session: Any, durationNs: Long) {
            (session as PerformanceHintManager.Session).reportActualWorkDuration(durationNs)
        }

        fun updateTargetWorkDuration(session: Any, targetDurationNs: Long) {
            (session as PerformanceHintManager.Session).updateTargetWorkDuration(targetDurationNs)
        }

        fun close(session: Any) {
            (session as PerformanceHintManager.Session).close()
        }
    }

}
