package com.hinnka.mycamera.ui.camera

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import com.hinnka.mycamera.ui.components.ScaledIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.ui.theme.AccentColor
import com.hinnka.mycamera.R
import com.hinnka.mycamera.camera.CameraInfo
import com.hinnka.mycamera.camera.LensType
import com.hinnka.mycamera.viewmodel.CameraViewModel
import com.hinnka.mycamera.video.CaptureMode
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
    val currentCameraIdState by rememberUpdatedState(currentCameraId)

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
    val macroCameras = remember(availableCameras) {
        availableCameras.filter { it.lensType == LensType.BACK_MACRO }
    }

    val cameraState by viewModel.state.collectAsState()
    val isLogVideo = cameraState.captureMode == CaptureMode.VIDEO &&
        cameraState.videoConfig.logProfile.isEnabled
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
    var customZoomStop by remember { mutableStateOf<Float?>(null) }
    var replacedStopIndex by remember { mutableIntStateOf(-1) }
    var originalStopRatio by remember { mutableFloatStateOf(0f) }

    fun closeContinuousZoomBar() {
        if (!isContinuousZooming) return

        isContinuousZooming = false
        val settlement = settleContinuousZoomStop(
            zoomStops = zoomStops,
            zoomRatio = internalZoomRatio
        )
        customZoomStop = settlement.customZoomStop
        replacedStopIndex = settlement.replacedStopIndex
        originalStopRatio = settlement.originalStopRatio
        settlement.snapZoomStop?.let(onZoomChange)
    }

    // Keep the local value synchronized unless a drag or continuous-zoom interaction is active.
    LaunchedEffect(zoomRatio, isDragging, isContinuousZooming, viewModel.isZooming) {
        if (viewModel.isZooming || (!isDragging && !isContinuousZooming && customZoomStop == null)) {
            internalZoomRatio = zoomRatio
        }
    }

    // 监听外部变焦状态 (如预览 Pinch)
    LaunchedEffect(viewModel.isZooming) {
        if (viewModel.isZooming) {
            isContinuousZooming = true
            customZoomStop = null
            replacedStopIndex = -1
        }
        lastInteractionTime = System.currentTimeMillis()
    }

    LaunchedEffect(zoomStops, customZoomStop, replacedStopIndex) {
        if (customZoomStop != null && replacedStopIndex !in zoomStops.indices) {
            customZoomStop = null
            replacedStopIndex = -1
        }
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
                        customZoomStop = null
                        replacedStopIndex = -1
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
                        isDragging = false
                        dragAccumulated = 0f
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    onDragCancel = {
                        isDragging = false
                        dragAccumulated = 0f
                        lastInteractionTime = System.currentTimeMillis()
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
                    imageVector = if (isLogVideo) AppIcons.Palette else AppIcons.AutoAwesome,
                    contentDescription = stringResource(if (isLogVideo) R.string.filter else R.string.filters_panel),
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
                    onClose = {
                        lastInteractionTime = System.currentTimeMillis()
                        closeContinuousZoomBar()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val effectiveStops = remember(zoomStops, customZoomStop, replacedStopIndex) {
                    buildEffectiveZoomStops(
                        zoomStops = zoomStops,
                        customZoomStop = customZoomStop,
                        replacedStopIndex = replacedStopIndex
                    )
                }
                val stopItems = remember(effectiveStops, availableCameras, currentCamera) {
                    buildZoomStopItems(
                        stops = effectiveStops,
                        availableCameras = availableCameras,
                        currentCamera = currentCamera
                    )
                }
                ZoomRuler(
                    zoomRatio = internalZoomRatio,
                    stopItems = stopItems,
                    macroCameras = macroCameras,
                    currentCameraId = currentCameraIdState,
                    mainCamera = mainCamera,
                    displayMode = displayMode,
                    onZoomChange = { stop ->
                        val targetStop = if (customZoomStop != null && stop == customZoomStop) originalStopRatio else stop
                        onZoomStopClick(targetStop)
                        customZoomStop = null
                        replacedStopIndex = -1
                    },
                    onLensSwitch = onLensSwitch,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
    }
}

private fun buildEffectiveZoomStops(
    zoomStops: List<Float>,
    customZoomStop: Float?,
    replacedStopIndex: Int
): List<Float> {
    if (customZoomStop == null || replacedStopIndex !in zoomStops.indices) {
        return zoomStops
    }

    return zoomStops.toMutableList().apply {
        this[replacedStopIndex] = customZoomStop
    }
}

@Composable
private fun ZoomRuler(
    zoomRatio: Float,
    stopItems: List<ZoomStopItem>,
    macroCameras: List<CameraInfo>,
    currentCameraId: String,
    mainCamera: CameraInfo?,
    displayMode: ZoomDisplayMode,
    onZoomChange: (Float) -> Unit,
    onLensSwitch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = AccentColor
    val inactiveColor = Color.White
    val stopsState by rememberUpdatedState(stopItems)

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val hasMacroSeparator = stopsState.isNotEmpty() && macroCameras.isNotEmpty()
        val adaptiveMetrics = calculateZoomRulerAdaptiveMetrics(
            availableSpace = maxWidth,
            itemCount = stopsState.size + macroCameras.size,
            separatorCount = if (hasMacroSeparator) 1 else 0
        )

        Row(
            modifier = Modifier
                .width(adaptiveMetrics.rulerLength)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(
                adaptiveMetrics.spacing,
                Alignment.CenterHorizontally
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isCurrentMacro = macroCameras.any { it.cameraId == currentCameraId }
            val selectedStopIndex = if (isCurrentMacro) -1 else stopsState.indices.minByOrNull {
                val item = stopsState[it]
                if (item.containsCamera(currentCameraId) && abs(item.zoomRatio - zoomRatio) <= 0.01f) {
                    -1f
                } else {
                    abs(item.zoomRatio - zoomRatio)
                }
            }

            stopsState.forEachIndexed { index, item ->
                val stop = item.zoomRatio
                val isSelected = index == selectedStopIndex && abs(stop - zoomRatio) <= 0.01f
                val text = when (displayMode) {
                    ZoomDisplayMode.ZOOM_RATIO -> formatZoomRatioLabel(stop)
                    ZoomDisplayMode.FOCAL_LENGTH -> zoomRatioToFocalLength(stop, mainCamera)
                } + if (item.isCustomLensStop) "*" else ""
                val style = TextStyle(
                    fontSize = if (isSelected) adaptiveMetrics.selectedFontSize else adaptiveMetrics.normalFontSize,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) activeColor else inactiveColor,
                    textAlign = TextAlign.Center,
                    textDecoration = if (item.isLensStop) TextDecoration.Underline else TextDecoration.None,
                    shadow = ViewfinderTextShadow
                )

                Box(
                    modifier = Modifier
                        .size(adaptiveMetrics.itemSize)
                        .autoRotate()
                        .pointerInput(item.cameraIds, stop, currentCameraId) {
                            detectTapGestures {
                                val cameraId = item.targetCameraId(currentCameraId)
                                if (cameraId != null && cameraId != currentCameraId) {
                                    onLensSwitch(cameraId)
                                } else {
                                    onZoomChange(stop)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = text,
                        style = style,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                    if (item.hasVariantBadge) {
                        val badgeText = item.selectedVariantIndex(currentCameraId)
                            ?.takeIf { isSelected }
                            ?.toString()
                            ?: item.variantCount.toString()
                        ZoomStopVariantBadge(
                            text = badgeText,
                            isSelected = isSelected,
                            activeColor = activeColor,
                            inactiveColor = inactiveColor,
                            itemSize = adaptiveMetrics.itemSize,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 1.dp, end = 1.dp)
                        )
                    }
                }
            }

            if (macroCameras.isNotEmpty()) {
                if (hasMacroSeparator) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(adaptiveMetrics.separatorLength)
                            .background(Color.White.copy(alpha = 0.2f))
                    )
                }

                macroCameras.forEach { macroCamera ->
                    val isSelected = macroCamera.cameraId == currentCameraId
                    Box(
                        modifier = Modifier
                            .size(adaptiveMetrics.itemSize)
                            .autoRotate()
                            .pointerInput(macroCamera.cameraId) {
                                detectTapGestures { onLensSwitch(macroCamera.cameraId) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.FilterVintage,
                            contentDescription = "Macro",
                            tint = if (isSelected) activeColor else inactiveColor,
                            modifier = Modifier.size(
                                if (isSelected) adaptiveMetrics.selectedIconSize else adaptiveMetrics.normalIconSize
                            )
                        )
                    }
                }
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
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val rulerYellow = Color(0xFFFFD52A)
    val closeDescription = stringResource(R.string.close)
    val currentText = when (displayMode) {
        ZoomDisplayMode.ZOOM_RATIO -> formatZoomRatioLabel(zoomRatio)
        ZoomDisplayMode.FOCAL_LENGTH -> zoomRatioToFocalLength(zoomRatio, mainCamera) + "mm"
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Keep the ruler track at the native 32dp PhotonCamera control height.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(0xFF292929))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val safeZoom = zoomRatio.coerceAtLeast(0.001f).toDouble()
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val halfTickRange = (size.width / 2f - 48.dp.toPx()).coerceAtLeast(0f)
                if (halfTickRange > 0f) {
                    // Logarithmic ticks glide beneath the fixed center indicator as zoom changes.
                    val logRangePerPixel = ln(1.5) / (halfTickRange * 2.0)
                    val visibleMin = maxOf(
                        minZoom.toDouble(),
                        safeZoom * exp(-halfTickRange * logRangePerPixel)
                    )
                    val visibleMax = minOf(
                        maxZoom.toDouble(),
                        safeZoom * exp(halfTickRange * logRangePerPixel)
                    )
                    val tickLogStep = ln(1.01)
                    if (visibleMin > 0.0 && visibleMax >= visibleMin) {
                        val firstTick = kotlin.math.ceil(ln(visibleMin) / tickLogStep).toInt()
                        val lastTick = kotlin.math.floor(ln(visibleMax) / tickLogStep).toInt()
                        for (tickIndex in firstTick..lastTick) {
                            val tickZoom = exp(tickIndex * tickLogStep)
                            val x = centerX +
                                (ln(tickZoom / safeZoom) / logRangePerPixel).toFloat()
                            if (x < centerX - halfTickRange || x > centerX + halfTickRange) continue

                            val majorTick = tickIndex % 5 == 0
                            val tickHeight = if (majorTick) 17.dp.toPx() else 11.dp.toPx()
                            drawLine(
                                color = Color.White.copy(alpha = if (majorTick) 0.86f else 0.58f),
                                start = Offset(x, centerY - tickHeight / 2f),
                                end = Offset(x, centerY + tickHeight / 2f),
                                strokeWidth = if (majorTick) 1.35.dp.toPx() else 1.dp.toPx()
                            )
                        }
                    }
                }
            }

            // Fixed yellow indicator and close control, matching the reference screenshot.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(2.5.dp)
                    .height(22.dp)
                    .clip(CircleShape)
                    .background(rulerYellow)
            )

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .size(25.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3A3A3A))
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = closeDescription },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(11.dp)) {
                    val stroke = 1.7.dp.toPx()
                    val inset = size.width * 0.22f
                    drawLine(
                        color = Color.White.copy(alpha = 0.94f),
                        start = Offset(inset, inset),
                        end = Offset(size.width - inset, size.height - inset),
                        strokeWidth = stroke
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.94f),
                        start = Offset(size.width - inset, inset),
                        end = Offset(inset, size.height - inset),
                        strokeWidth = stroke
                    )
                }
            }
        }

        // Keep the live zoom readout just above the pill, as in the screenshot.
        Text(
            text = currentText,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-20).dp),
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = rulerYellow,
                shadow = ViewfinderTextShadow
            ),
            maxLines = 1,
            softWrap = false
        )
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
