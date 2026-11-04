package com.hinnka.mycamera.ui.camera

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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

    // Animate the center control expanding from the preset stops into the full slider.
    val centerHorizontalPadding by animateDpAsState(
        targetValue = if (isContinuousZooming) 0.dp else 40.dp,
        animationSpec = tween(durationMillis = 220)
    )

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
                        val wasContinuousZooming = isContinuousZooming
                        if (!wasContinuousZooming) {
                            dragAccumulated += dragAmount
                            // Expand only on a left-to-right swipe. Once expanded, both
                            // directions scrub the slider normally.
                            if (dragAccumulated > 10.dp.toPx()) {
                                isContinuousZooming = true
                            }
                        }

                        if (isContinuousZooming) {
                            change.consume()
                            lastInteractionTime = System.currentTimeMillis()
                            // Map horizontal movement to the full logarithmic zoom range.
                            // The first swipe also moves the handle by the distance that
                            // triggered the morph, so the UI and zoom stay in sync.
                            val effectiveDrag = if (wasContinuousZooming) dragAmount else dragAccumulated
                            val safeMinZoom = minZoom.coerceAtLeast(0.001f)
                            val safeMaxZoom = maxZoom.coerceAtLeast(safeMinZoom + 0.001f)
                            val logZoomRange = ln((safeMaxZoom / safeMinZoom).toDouble())
                            val normalizedDrag = effectiveDrag / size.width.coerceAtLeast(1).toFloat()
                            val newZoom = (
                                internalZoomRatio * exp(normalizedDrag.toDouble() * logZoomRange).toFloat()
                            ).coerceIn(minZoom, maxZoom)
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
                .padding(horizontal = centerHorizontalPadding)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = isContinuousZooming,
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(durationMillis = 200)) +
                        scaleIn(initialScale = 0.94f, animationSpec = tween(durationMillis = 200))) togetherWith
                        (fadeOut(animationSpec = tween(durationMillis = 130)) +
                            scaleOut(targetScale = 0.98f, animationSpec = tween(durationMillis = 130))) using
                        SizeTransform(clip = false)
                }
            ) { showContinuousSlider ->
                if (!isCameraReady) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = AccentColor,
                        strokeWidth = 2.dp
                    )
                } else if (showContinuousSlider) {
                    ZoomContinuousSlider(
                        zoomRatio = internalZoomRatio,
                        minZoom = minZoom,
                        maxZoom = maxZoom,
                        mainCamera = mainCamera,
                        displayMode = displayMode,
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
private fun ZoomContinuousSlider(
    zoomRatio: Float,
    minZoom: Float,
    maxZoom: Float,
    mainCamera: CameraInfo?,
    displayMode: ZoomDisplayMode,
    modifier: Modifier = Modifier
) {
    val handleColor = Color(0xFFE8B8E8)
    val valuePillColor = Color(0xFFF2DDF1)
    val currentText = when (displayMode) {
        ZoomDisplayMode.ZOOM_RATIO -> formatZoomRatioLabel(zoomRatio)
        ZoomDisplayMode.FOCAL_LENGTH -> zoomRatioToFocalLength(zoomRatio, mainCamera) + "mm"
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Capture the layout width before nested iteration lambdas, which do not
        // inherit BoxWithConstraintsScope's implicit receiver.
        val sliderWidth = maxWidth
        val safeMinZoom = minZoom.coerceAtLeast(0.001f)
        val safeMaxZoom = maxZoom.coerceAtLeast(safeMinZoom + 0.001f)
        val logMinimum = ln(safeMinZoom.toDouble())
        val logRange = (ln(safeMaxZoom.toDouble()) - logMinimum).coerceAtLeast(0.0001)
        val safeCurrentZoom = zoomRatio.coerceIn(safeMinZoom, safeMaxZoom)
        val progress = (
            (ln(safeCurrentZoom.toDouble()) - logMinimum) / logRange
        ).toFloat().coerceIn(0f, 1f)

        val sideInset = 12.dp
        val usableWidth = (maxWidth - sideInset * 2).coerceAtLeast(0.dp)
        val handleOffset = (
            sideInset + usableWidth * progress - 2.dp
        ).coerceIn(0.dp, (maxWidth - 4.dp).coerceAtLeast(0.dp))
        val valuePillOffset = (
            sideInset + usableWidth * progress - 28.dp
        ).coerceIn(0.dp, (maxWidth - 56.dp).coerceAtLeast(0.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(0xD91D1D1D))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val insetPx = sideInset.toPx()
                val tickWidth = (size.width - insetPx * 2).coerceAtLeast(0f)
                val tickCount = (tickWidth / 6.dp.toPx()).roundToInt().coerceIn(12, 100)
                val tickCenterY = 11.dp.toPx()

                // The scale stays stationary; the highlighted handle travels across it.
                for (index in 0..tickCount) {
                    val x = insetPx + tickWidth * index / tickCount
                    val majorTick = index % 5 == 0
                    val tickHeight = if (majorTick) 13.dp.toPx() else 7.dp.toPx()
                    drawLine(
                        color = Color.White.copy(alpha = if (majorTick) 0.72f else 0.38f),
                        start = Offset(x, tickCenterY - tickHeight / 2f),
                        end = Offset(x, tickCenterY + tickHeight / 2f),
                        strokeWidth = if (majorTick) 1.2.dp.toPx() else 0.85.dp.toPx()
                    )
                }

                // Emphasize the familiar optical zoom stops without moving the scale.
                listOf(0.5f, 1f, 2f, 4f, 8f, 16f).forEach { stop ->
                    if (stop >= safeMinZoom && stop <= safeMaxZoom) {
                        val stopProgress = (
                            (ln(stop.toDouble()) - logMinimum) / logRange
                        ).toFloat()
                        val x = insetPx + tickWidth * stopProgress
                        drawLine(
                            color = Color.White.copy(alpha = 0.94f),
                            start = Offset(x, 3.dp.toPx()),
                            end = Offset(x, 19.dp.toPx()),
                            strokeWidth = 1.35.dp.toPx()
                        )
                    }
                }
            }

            listOf(0.5f, 1f, 2f, 4f, 8f, 16f)
                .filter { it >= safeMinZoom && it <= safeMaxZoom }
                .forEach { stop ->
                    val stopProgress = (
                        (ln(stop.toDouble()) - logMinimum) / logRange
                    ).toFloat().coerceIn(0f, 1f)
                    val labelOffset = (
                        sideInset + usableWidth * stopProgress - 11.dp
                    ).coerceIn(0.dp, (sliderWidth - 22.dp).coerceAtLeast(0.dp))
                    Text(
                        text = if (stop == 0.5f) ".5" else stop.roundToInt().toString(),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = labelOffset, y = (-1).dp)
                            .width(22.dp),
                        style = TextStyle(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.88f),
                            textAlign = TextAlign.Center,
                            shadow = ViewfinderTextShadow
                        ),
                        maxLines = 1,
                        softWrap = false
                    )
                }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = handleOffset, y = 3.dp)
                    .width(4.dp)
                    .height(17.dp)
                    .clip(CircleShape)
                    .background(handleColor)
            )
        }

        // The live value pill follows the draggable handle, matching the reference UI.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = valuePillOffset, y = (-22).dp)
                .width(56.dp)
                .clip(CircleShape)
                .background(valuePillColor)
                .padding(vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = currentText,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF28202B)
                ),
                maxLines = 1,
                softWrap = false
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
