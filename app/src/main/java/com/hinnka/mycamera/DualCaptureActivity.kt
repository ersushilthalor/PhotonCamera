package com.hinnka.mycamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hinnka.mycamera.ui.theme.PhotonCameraTheme
import com.hinnka.mycamera.video.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class DualCaptureActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ASPECT_RATIO = "dual_capture.aspect_ratio"
        const val EXTRA_OPEN_GATE_RATIO = "dual_capture.open_gate_ratio"
        const val EXTRA_FPS = "dual_capture.fps"
        const val EXTRA_CODEC_MIME = "dual_capture.codec_mime"
        const val EXTRA_BITRATE_MBPS = "dual_capture.bitrate_mbps"
        const val EXTRA_RESOLUTION = "dual_capture.resolution"
        private const val PERMISSION_REQUEST = 8301
    }

    private var support by mutableStateOf<DualCaptureSupport?>(null)
    private var selectedOption by mutableStateOf<DualCaptureResolutionOption?>(null)
    private var loading by mutableStateOf(true)
    private var cameraReady by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private var savedMessage by mutableStateOf<String?>(null)
    private var previewSurface: Surface? = null
    private var previewSize = IntSize(1, 1)
    private var engine: DualCaptureEngine? = null
    private var engineStarted = false
    private var closeAfterSave = false
    private var dualLayout by mutableStateOf(DualCaptureLayout.PICTURE_IN_PICTURE)
    private var pipX by mutableFloatStateOf(0.62f)
    private var pipY by mutableFloatStateOf(0.68f)

    private val requestedAspectRatio: VideoAspectRatio by lazy {
        runCatching {
            VideoAspectRatio.valueOf(
                intent.getStringExtra(EXTRA_ASPECT_RATIO) ?: VideoAspectRatio.RATIO_16_9.name
            )
        }.getOrDefault(VideoAspectRatio.RATIO_16_9)
    }
    private val openGateAspect: Float by lazy {
        intent.getFloatExtra(EXTRA_OPEN_GATE_RATIO, 3f / 4f).coerceIn(0.1f, 1f)
    }
    private val requestedFps: Int by lazy {
        intent.getIntExtra(EXTRA_FPS, 30).coerceIn(24, 120)
    }
    private val requestedCodec: String by lazy {
        intent.getStringExtra(EXTRA_CODEC_MIME) ?: VideoCodec.H264.mimeType
    }
    private val requestedBitrateMbps: Int by lazy {
        intent.getIntExtra(EXTRA_BITRATE_MBPS, 30).coerceIn(8, 120)
    }
    private val requestedResolution: VideoResolutionPreset by lazy {
        runCatching {
            VideoResolutionPreset.valueOf(
                intent.getStringExtra(EXTRA_RESOLUTION) ?: VideoResolutionPreset.FHD_1080P.name
            )
        }.getOrDefault(VideoResolutionPreset.FHD_1080P)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        engine = DualCaptureEngine(
            context = this,
            onReady = {
                runOnUiThread {
                    cameraReady = true
                    errorMessage = null
                }
            },
            onError = { message ->
                runOnUiThread {
                    cameraReady = false
                    recording = false
                    errorMessage = message
                }
            },
            onRecordingFinished = { uri ->
                runOnUiThread {
                    recording = false
                    savedMessage = if (uri != null) "Video saved to PhotonCamera gallery." else "Recording finished."
                    Toast.makeText(this, savedMessage, Toast.LENGTH_SHORT).show()
                    if (closeAfterSave) {
                        closeAfterSave = false
                        finish()
                    }
                }
            }
        )

        setContent {
            PhotonCameraTheme {
                DualCaptureScreen(
                    aspectRatio = requestedAspectRatio.getPortraitAspectRatio(openGateAspect),
                    support = support,
                    selectedOption = selectedOption,
                    loading = loading,
                    cameraReady = cameraReady,
                    recording = recording,
                    errorMessage = errorMessage,
                    savedMessage = savedMessage,
                    layout = dualLayout,
                    pipX = pipX,
                    pipY = pipY,
                    onPreviewSurface = ::onPreviewSurfaceAvailable,
                    onPreviewSizeChanged = ::onPreviewSizeChanged,
                    onLayoutChange = { value ->
                        dualLayout = value
                        engine?.setLayout(value)
                    },
                    onResolutionChange = ::changeResolution,
                    onPipPositionChange = { x, y ->
                        pipX = x
                        pipY = y
                        engine?.setPipPosition(x, y)
                    },
                    onRecordClick = ::toggleRecording,
                    onClose = ::closeScreen
                )
            }
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeScreen()
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST)
        } else {
            loadCapabilities()
        }
    }

    private fun loadCapabilities() {
        loading = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                DualCaptureCapabilities.resolve(
                    context = applicationContext,
                    aspectRatio = requestedAspectRatio,
                    openGatePortraitAspectRatio = openGateAspect,
                    fps = requestedFps,
                    codecMime = requestedCodec
                )
            }
            loading = false
            support = result.support
            errorMessage = result.message.takeIf { result.support == null }
            result.support?.let { available ->
                selectedOption = available.options.firstOrNull { it.preset == requestedResolution }
                    ?: available.defaultOption
                startEngineIfPossible()
            }
        }
    }

    private fun onPreviewSurfaceAvailable(surface: Surface) {
        previewSurface = surface
        if (engineStarted) {
            engine?.updatePreview(surface, previewSize.width, previewSize.height)
        } else {
            startEngineIfPossible()
        }
    }

    private fun onPreviewSizeChanged(size: IntSize) {
        previewSize = IntSize(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        val surface = previewSurface
        if (surface != null && engineStarted) {
            engine?.updatePreview(surface, previewSize.width, previewSize.height)
        }
    }

    private fun startEngineIfPossible() {
        val available = support ?: return
        val selected = selectedOption ?: return
        val surface = previewSurface ?: return
        if (!surface.isValid || engineStarted) return
        cameraReady = false
        errorMessage = null
        engineStarted = true
        engine?.start(
            preview = surface,
            width = previewSize.width,
            height = previewSize.height,
            capabilities = available,
            selected = selected
        )
        engine?.setLayout(dualLayout)
        engine?.setPipPosition(pipX, pipY)
    }

    private fun changeResolution(option: DualCaptureResolutionOption) {
        if (recording || selectedOption?.preset == option.preset) return
        selectedOption = option
        cameraReady = false
        errorMessage = null
        engineStarted = true
        engine?.changeResolution(option)
    }

    private fun toggleRecording() {
        val selected = selectedOption ?: return
        val available = support ?: return
        if (recording) {
            engine?.stopRecording()
            recording = false
        } else {
            if (!cameraReady) {
                Toast.makeText(this, "Wait for both camera streams to become ready.", Toast.LENGTH_SHORT).show()
                return
            }
            savedMessage = null
            errorMessage = null
            recording = true
                lifecycleScope.launch(Dispatchers.IO) {
                engine?.startRecording(
                    selected = selected,
                    fps = requestedFps,
                    bitrateMbps = requestedBitrateMbps,
                    codecMime = available.codecMime
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                loadCapabilities()
            } else {
                loading = false
                errorMessage = "Camera permission is required for dual capture."
            }
        }
    }

    private fun closeScreen() {
        if (recording) {
            closeAfterSave = true
            engine?.stopRecording()
            recording = false
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        super.onDestroy()
    }
}

@Composable
private fun DualCaptureScreen(
    aspectRatio: Float,
    support: DualCaptureSupport?,
    selectedOption: DualCaptureResolutionOption?,
    loading: Boolean,
    cameraReady: Boolean,
    recording: Boolean,
    errorMessage: String?,
    savedMessage: String?,
    layout: DualCaptureLayout,
    pipX: Float,
    pipY: Float,
    onPreviewSurface: (Surface) -> Unit,
    onPreviewSizeChanged: (IntSize) -> Unit,
    onLayoutChange: (DualCaptureLayout) -> Unit,
    onResolutionChange: (DualCaptureResolutionOption) -> Unit,
    onPipPositionChange: (Float, Float) -> Unit,
    onRecordClick: () -> Unit,
    onClose: () -> Unit
) {
    val latestPipX by rememberUpdatedState(pipX)
    val latestPipY by rememberUpdatedState(pipY)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("DUAL CAPTURE", color = Color.White, fontSize = 15.sp)
                Text(
                    when {
                        loading -> "Checking real camera support…"
                        cameraReady -> "FRONT + REAR READY"
                        support != null -> "Opening both cameras…"
                        else -> "Not supported"
                    },
                    color = if (cameraReady) Color(0xFF80D8A2) else Color.LightGray,
                    fontSize = 10.sp
                )
            }
            if (recording) {
                Text("● REC", color = Color.Red, fontSize = 13.sp)
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            val ratio = aspectRatio.coerceIn(0.1f, 1f)
            val previewWidth = minOf(maxWidth, maxHeight * ratio)
            val previewHeight = previewWidth / ratio
            Box(
                modifier = Modifier
                    .size(previewWidth, previewHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF101010))
            ) {
                AndroidView(
                    factory = { context ->
                        SurfaceView(context).also { view ->
                            view.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    onPreviewSurface(holder.surface)
                                }
                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {
                                    onPreviewSurface(holder.surface)
                                    onPreviewSizeChanged(IntSize(width, height))
                                }
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    // The Activity owns the camera engine and releases it on exit.
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (layout == DualCaptureLayout.PICTURE_IN_PICTURE) {
                    val insetWidth = previewWidth * 0.34f
                    val insetHeight = previewHeight * 0.24f
                    Box(
                        modifier = Modifier
                            .offset(
                                x = (previewWidth - insetWidth) * pipX.coerceIn(0f, 1f),
                                y = (previewHeight - insetHeight) * pipY.coerceIn(0f, 1f)
                            )
                            .size(insetWidth, insetHeight)
                            .clip(RoundedCornerShape(11.dp))
                            .border(
                                BorderStroke(2.dp, Color.White),
                                RoundedCornerShape(11.dp)
                            )
                            .pointerInput(previewWidth, previewHeight) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    val widthPx = (previewWidth - insetWidth).toPx().coerceAtLeast(1f)
                                    val heightPx = (previewHeight - insetHeight).toPx().coerceAtLeast(1f)
                                    val nextX = (latestPipX + drag.x / widthPx).coerceIn(0f, 1f)
                                    val nextY = (latestPipY + drag.y / heightPx).coerceIn(0f, 1f)
                                    onPipPositionChange(nextX, nextY)
                                }
                            }
                    )
                }
                if (layout == DualCaptureLayout.STACKED) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.7f))
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = layout == DualCaptureLayout.PICTURE_IN_PICTURE,
                onClick = { onLayoutChange(DualCaptureLayout.PICTURE_IN_PICTURE) },
                label = { Text("PiP") },
                enabled = !recording
            )
            Spacer(Modifier.width(10.dp))
            FilterChip(
                selected = layout == DualCaptureLayout.STACKED,
                onClick = { onLayoutChange(DualCaptureLayout.STACKED) },
                label = { Text("UP / DOWN") },
                enabled = !recording
            )
        }

        if (support != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                support.options.forEach { option ->
                    FilterChip(
                        selected = selectedOption?.preset == option.preset,
                        onClick = { onResolutionChange(option) },
                        enabled = !recording && (cameraReady || errorMessage != null),
                        label = { Text(option.preset.displayName, fontSize = 12.sp) },
                        modifier = Modifier.padding(horizontal = 3.dp)
                    )
                }
            }
            selectedOption?.let { option ->
                Text(
                    text = "Rear ${option.backInputSize.width}×${option.backInputSize.height}  •  Front ${option.frontInputSize.width}×${option.frontInputSize.height}",
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    text = "Saved video: ${option.outputSize.width}×${option.outputSize.height} @ ${support.fps} fps",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        errorMessage?.let {
            Text(
                it,
                color = Color(0xFFFF8A80),
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        savedMessage?.let {
            Text(
                it,
                color = Color(0xFF80D8A2),
                fontSize = 10.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = onRecordClick,
                enabled = cameraReady || recording,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (recording) Color(0xFFE53935) else Color.White,
                    contentColor = if (recording) Color.White else Color.Black,
                    disabledContainerColor = Color.DarkGray
                ),
                modifier = Modifier.size(66.dp)
            ) {
                Text(if (recording) "STOP" else "REC", fontSize = 12.sp)
            }
        }
    }
}
