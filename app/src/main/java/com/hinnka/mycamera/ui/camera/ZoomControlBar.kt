package com.hinnka.mycamera.ui.camera

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.drawText
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.ui.theme.AccentColor
import com.hinnka.mycamera.R
import com.hinnka.mycamera.camera.CameraInfo
import com.hinnka.mycamera.camera.LensType
import com.hinnka.mycamera.viewmodel.CameraViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import com.hinnka.mycamera.ui.icons.AppIcons

/**
 * 显示模式：变焦倍率 or 35mm等效焦距
 */
enum class ZoomDisplayMode {
    ZOOM_RATIO,      // 显示 0.6x, 1x, 2x 等
    FOCAL_LENGTH;    // 显示 35mm, 50mm, 85mm 等

    fun next(): ZoomDisplayMode {
        return if (this == ZOOM_RATIO) FOCAL_LENGTH else ZOOM_RATIO
    }

    companion object {
        fun fromPersistedName(name: String?): ZoomDisplayMode {
            return entries.firstOrNull { it.name == name } ?: FOCAL_LENGTH
        }
    }
}

@Composable
fun ZoomControlBar(
    viewModel: CameraViewModel,
    zoomRatio: Float,
    availableCameras: List<CameraInfo>,
    currentCameraId: String,
    onZoomChange: (Float) -> Unit,
    onZoomStopClick: (Float) -> Unit,
    onLensSwitch: (String) -> Unit,
    onFilterClick: () -> Unit,
    onFilterButtonBoundsChanged: (Rect) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val displayMode by viewModel.zoomDisplayMode.collectAsState()

    val currentCamera = availableCameras.find { it.cameraId == currentCameraId }
    val isCameraReady = currentCamera != null

    // 获取当前相机信息
    val mainCamera =
        availableCameras.find { it.lensType == if (currentCamera?.lensType == LensType.FRONT) LensType.FRONT else LensType.BACK_MAIN }

    // 根据可用相机计算变焦档位
    val customFocalLengths by viewModel.customFocalLengths.collectAsState(initial = emptyList())
    val hiddenFocalLengths by viewModel.hiddenFocalLengths.collectAsState(initial = emptyList())
    val lensZoomStops = viewModel.calculateLensZoomStops(availableCameras, currentCamera)
    val zoomStops = viewModel.allZoomStops(lensZoomStops, mainCamera, currentCamera, customFocalLengths, hiddenFocalLengths)

    val cameraState by viewModel.state.collectAsState()
    val minZoom = remember(cameraState.availableCameras) {
        cameraState.availableCameras.filter { it.lensType != LensType.FRONT }.minOfOrNull { it.minZoom * it.displayIntrinsicZoomRatio } ?: 1f
    }
    val maxZoom = remember(cameraState.availableCameras) {
        cameraState.availableCameras.filter { it.lensType != LensType.FRONT }.maxOfOrNull { it.maxZoom * it.displayIntrinsicZoomRatio } ?: 20f
    }

    var isContinuousZooming by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(0L) }
    // 使用内部状态暂存变焦值，避免 StateFlow 异步导致的“拉回”现象
    var internalZoomRatio by remember { mutableFloatStateOf(zoomRatio) }
    var isDragging by remember { mutableStateOf(false) }
    
    fun closeContinuousZoomBar() {
        if (!isContinuousZooming) return

        isContinuousZooming = false
        val settlement = settleContinuousZoomStop(
            zoomStops = zoomStops,
            zoomRatio = internalZoomRatio
        )
        settlement.snapZoomStop?.let(onZoomChange)
    }

    // Keep the local value synchronized unless a drag or continuous-zoom interaction is active.
    LaunchedEffect(zoomRatio, isDragging, isContinuousZooming, viewModel.isZooming) {
        if (viewModel.isZooming || (!isDragging && !isContinuousZooming)) {
            internalZoomRatio = zoomRatio
        }
    }

    // 监听外部变焦状态 (如预览 Pinch)
    LaunchedEffect(viewModel.isZooming) {
        if (viewModel.isZooming) {
            isContinuousZooming = true
        }
        lastInteractionTime = System.currentTimeMillis()
    }

    // 自动恢复默认 UI 的定时器
    LaunchedEffect(isContinuousZooming, lastInteractionTime, isDragging, viewModel.isZooming) {
        if (isContinuousZooming && !isDragging && !viewModel.isZooming) {
            delay(2000)
            if (isContinuousZooming && !isDragging && !viewModel.isZooming) {
                closeContinuousZoomBar()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(CameraControlsLayoutDefaults.ZoomBarHeight)
            .pointerInput(isCameraReady, minZoom, maxZoom, zoomStops) {
                if (!isCameraReady) return@pointerInput
                var dragAccumulated = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragAccumulated = 0f
                        isDragging = true
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (!isContinuousZooming) {
                            dragAccumulated += dragAmount
                            if (abs(dragAccumulated) > 10.dp.toPx()) {
                                isContinuousZooming = true
                            }
                        }

                        if (isContinuousZooming) {
                            change.consume()
                            lastInteractionTime = System.currentTimeMillis()
                            // 使用指数变焦公式计算新倍率
                            // Match Oppocam's 1.35x zoom-slider sensitivity multiplier while retaining
                            // this slider's logarithmic zoom mapping.
                            val sensitivity = 0.0027f
                            val newZoom = (internalZoomRatio * exp(-dragAmount.toDouble() * sensitivity).toFloat()).coerceIn(minZoom, maxZoom)
                            internalZoomRatio = newZoom
                            
                            onZoomChange(newZoom)
                        }
                    },
                    onDragEnd = {
                        val shouldCloseContinuousZoomBar = isContinuousZooming && !viewModel.isZooming
                        isDragging = false
                        dragAccumulated = 0f
                        lastInteractionTime = System.currentTimeMillis()
                        if (shouldCloseContinuousZoomBar) {
                            closeContinuousZoomBar()
                        }
                    },
                    onDragCancel = {
                        val shouldCloseContinuousZoomBar = isContinuousZooming && !viewModel.isZooming
                        isDragging = false
                        dragAccumulated = 0f
                        lastInteractionTime = System.currentTimeMillis()
                        if (shouldCloseContinuousZoomBar) {
                            closeContinuousZoomBar()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = !isContinuousZooming,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            // Display Mode Toggle (Left)
            IconButton(
                onClick = {
                    viewModel.saveZoomDisplayMode(displayMode.next())
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = AppIcons.SwapHoriz,
                    contentDescription = stringResource(R.string.toggle_display_mode),
                    modifier = Modifier
                        .size(32.dp)
                        .padding(8.dp),
                    tint = Color.White
                )
            }
        }

        AnimatedVisibility(
            visible = !isContinuousZooming,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            // 右侧滤镜按钮
            IconButton(
                onClick = onFilterClick,
                modifier = Modifier
                    .size(32.dp)
                    .onGloballyPositioned { coordinates ->
                        onFilterButtonBoundsChanged(coordinates.boundsInRoot())
                    }
            ) {
                Icon(
                    imageVector = AppIcons.AutoAwesome,
                    contentDescription = stringResource(R.string.filters_panel),
                    modifier = Modifier
                        .size(32.dp)
                        .padding(8.dp),
                    tint = AccentColor
                )
            }
        }

        // Zoom Ruler (Center)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = if (isContinuousZooming) 0.dp else 40.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (!isCameraReady) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = AccentColor,
                    strokeWidth = 2.dp
                )
            } else if (isContinuousZooming) {
                ZoomContinuousRuler(
                    zoomRatio = internalZoomRatio,
                    minZoom = minZoom,
                    maxZoom = maxZoom,
                    mainCamera = mainCamera,
                    displayMode = displayMode,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ZoomPresetPill(
                    zoomRatio = internalZoomRatio,
                    onPresetClick = { preset ->
                        val matchingLensCameras = availableCameras
                            .filter { camera ->
                                val matchesFacing = if (currentCamera?.lensType == LensType.FRONT) {
                                    camera.lensType == LensType.FRONT
                                } else {
                                    camera.lensType != LensType.FRONT &&
                                        camera.lensType != LensType.BACK_MACRO
                                }
                                matchesFacing &&
                                    camera.displayIntrinsicZoomRatio > 0f &&
                                    abs(camera.displayIntrinsicZoomRatio - preset) <= 0.01f
                            }
                            .sortedWith(compareBy<CameraInfo>({ it.displayIntrinsicZoomRatio }, { it.cameraId }))

                        val currentIndex = matchingLensCameras.indexOfFirst { it.cameraId == currentCameraId }
                        val targetCameraId = when {
                            matchingLensCameras.isEmpty() -> null
                            currentIndex >= 0 && matchingLensCameras.size > 1 ->
                                matchingLensCameras[(currentIndex + 1) % matchingLensCameras.size].cameraId
                            else -> matchingLensCameras.first().cameraId
                        }

                        if (targetCameraId != null && targetCameraId != currentCameraId) {
                            onLensSwitch(targetCameraId)
                        } else {
                            onZoomStopClick(preset)
                        }
                    },
                    modifier = Modifier.wrapContentWidth().fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun ZoomPresetPill(
    zoomRatio: Float,
    onPresetClick: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val basePresets = listOf(0.6f, 1f, 2f, 3f)
    val visiblePresets = if (zoomRatio >= 3f) basePresets + listOf(5f, 10f) else basePresets
    val selectedPreset = visiblePresets.firstOrNull { abs(it - zoomRatio) <= 0.05f }

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFF25242C))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        visiblePresets.forEach { preset ->
            val selected = selectedPreset != null && abs(selectedPreset - preset) <= 0.01f
            Box(
                modifier = Modifier
                    .width(37.dp)
                    .height(29.dp)
                    .clickable { onPresetClick(preset) },
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(29.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF484651))
                    )
                }
                val label = when {
                    abs(preset - 0.6f) <= 0.01f -> ".6"
                    else -> preset.toInt().toString()
                }
                Text(
                    text = if (selected) "${label}x" else label,
                    style = TextStyle(
                        fontSize = if (selected) 14.sp else 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * 连续变焦标尺
 */
@Composable
fun ZoomContinuousRuler(
    zoomRatio: Float,
    minZoom: Float,
    maxZoom: Float,
    mainCamera: CameraInfo?,
    displayMode: ZoomDisplayMode,
    modifier: Modifier = Modifier
) {
    val accentColor = AccentColor
    val textMeasurer = rememberTextMeasurer()

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 4.dp)) {
            val width = size.width
            val height = size.height
            val accent = accentColor
            
            // 绘制刻度。在当前 Zoom 附近绘制。
            // 我们在尺子上每隔一定的比例（比如 1.1倍）画一个大刻度。
            val zoomStep = 1.1f
            val visibleRatioRange = 1.5f // 屏幕宽度覆盖的变焦倍率范围 (约 1.5x)
            
            // 计算尺子上每像素对应的变焦倍率对数
            // ln(zoomRatio) 映射到中心。
            // ln(z) = ln(zoomRatio) + (x - center) * logRangePerPixel
            val logRangePerPixel = ln(visibleRatioRange.toDouble()) / width
            
            fun zoomToX(z: Float): Float {
                return (width / 2 + (ln(z.toDouble() / zoomRatio.toDouble()) / logRangePerPixel)).toFloat()
            }
            
            fun xToZoom(x: Float): Float {
                return (zoomRatio * exp((x - width / 2) * logRangePerPixel)).toFloat()
            }

            // 绘制主要刻度 (0.1, 0.5, 1, 2, 5, 10, etc.)
            // 为了简单，我们绘制 1.0, 2.0, 3.0 等整数，以及 0.5 的倍数
            val minVisibleZoom = xToZoom(0f)
            val maxVisibleZoom = xToZoom(width)
            
            val majorSteps = listOf(0.5f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 12f, 15f, 20f, 30f, 50f)
            majorSteps.forEach { stepValue ->
                if (stepValue in minVisibleZoom..maxVisibleZoom) {
                    val x = zoomToX(stepValue)
                    val tickHeight = 12.dp.toPx()
                    val tickWidth = 1.5.dp.toPx()
                    
                    drawRect(
                        color = Color.White.copy(alpha = 0.8f),
                        topLeft = Offset(x - tickWidth / 2f, height - tickHeight),
                        size = Size(tickWidth, tickHeight)
                    )
                    
                    val text = when (displayMode) {
                        ZoomDisplayMode.ZOOM_RATIO -> formatZoomRatioLabel(stepValue)
                        ZoomDisplayMode.FOCAL_LENGTH -> zoomRatioToFocalLength(stepValue, mainCamera)
                    }
                    
                    val textLayoutResult = textMeasurer.measure(
                        text = text,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            shadow = ViewfinderTextShadow
                        )
                    )
                    
                    drawText(
                        textLayoutResult = textLayoutResult,
                        topLeft = Offset(
                            x - textLayoutResult.size.width / 2f,
                            y = 0f
                        )
                    )
                }
            }

            // 绘制细刻度 (0.1x 的倍数)
            var minor = (Math.floor(minVisibleZoom * 10.0) / 10.0).toFloat()
            while (minor <= maxVisibleZoom) {
                if (minor >= minZoom && minor <= maxZoom) {
                    val x = zoomToX(minor)
                    if (x in 0f..width) {
                        val isMajor = majorSteps.any { abs(it - minor) < 0.01f }
                        if (!isMajor) {
                            val tickHeight = 6.dp.toPx()
                            val tickWidth = 1.dp.toPx()
                            drawRect(
                                color = Color.White.copy(alpha = 0.4f),
                                topLeft = Offset(x - tickWidth / 2f, height - tickHeight),
                                size = Size(tickWidth, tickHeight)
                            )
                        }
                    }
                }
                minor += 0.1f
            }
            
            // 绘制中心指示灯 (固定在中心)
            val centerX = width / 2f
            val indicatorWidth = 2.dp.toPx()
            
            // 绘制指示器背景发光
            drawCircle(
                color = accent.copy(alpha = 0.2f),
                center = Offset(centerX, height - 6.dp.toPx()),
                radius = 8.dp.toPx()
            )
            
            drawRect(
                color = accent,
                topLeft = Offset(centerX - indicatorWidth / 2f, height - 15.dp.toPx()),
                size = Size(indicatorWidth, 15.dp.toPx())
            )

            // 显示当前数值 (在尺子上方的浮窗感)
            val currentText = when (displayMode) {
                ZoomDisplayMode.ZOOM_RATIO -> formatZoomRatioLabel(zoomRatio)
                ZoomDisplayMode.FOCAL_LENGTH -> zoomRatioToFocalLength(zoomRatio, mainCamera) + "mm"
            }
            val currentTextLayout = textMeasurer.measure(
                text = currentText,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = accent,
                    shadow = ViewfinderTextShadow
                )
            )
            drawText(
                currentTextLayout,
                topLeft = Offset(centerX - currentTextLayout.size.width / 2f, -18.dp.toPx())
            )
        }
    }
}

/**
 * 变焦倍率转换为35mm等效焦距
 */
private fun zoomRatioToFocalLength(zoomRatio: Float, mainCamera: CameraInfo?): String {
    if (mainCamera == null || mainCamera.focalLength35mmEquivalent <= 0) {
        // 默认主摄为23mm等效
        return (23f * zoomRatio).toInt().toString()
    }
    return (mainCamera.focalLength35mmEquivalent * zoomRatio).roundToInt().toString()
}
