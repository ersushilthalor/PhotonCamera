package com.hinnka.mycamera.ui.camera

import android.media.AudioDeviceInfo
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.ui.theme.AccentColor
import com.hinnka.mycamera.ui.theme.OnAccentColor
import com.hinnka.mycamera.R
import com.hinnka.mycamera.camera.AspectRatio
import com.hinnka.mycamera.camera.MeteringMode
import com.hinnka.mycamera.data.OppoPhotoSettings
import com.hinnka.mycamera.raw.DcpInfo
import com.hinnka.mycamera.raw.HncsFilmCurveMode
import com.hinnka.mycamera.raw.RawRenderingEngine
import com.hinnka.mycamera.raw.RawToneMappingParameters
import com.hinnka.mycamera.raw.SpectralFilmSelection
import com.hinnka.mycamera.ui.components.AppModalBottomSheet
import com.hinnka.mycamera.ui.components.RawDcpLensOption
import com.hinnka.mycamera.ui.components.RawRenderingEngineSettingsPanel
import com.hinnka.mycamera.video.*
import com.hinnka.mycamera.video.VideoCodec
import com.hinnka.mycamera.ui.icons.AppIcons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import com.hinnka.mycamera.ui.components.LiquidGlassBackdrop
import com.hinnka.mycamera.ui.components.LiquidGlassSurface
import com.hinnka.mycamera.ui.components.LiquidGlassStyle

private enum class VideoSettingPanel {
    ASPECT_RATIO,
    LOG_PROFILE,
    BITRATE,
    CODEC,
    MICROPHONE,
    FORMAT
}

private val CameraTopSheetContentTopPadding = 32.dp

private val PhotoQuickMenuGlassStyle = LiquidGlassStyle(
    blurRadius = 18.dp,
    refractionHeight = 24.dp,
    refractionAmount = 18.dp,
    saturation = 1.18f,
    tint = Color(0x68090A10),
    highlightWidth = 0.8.dp,
    highlightBlur = 0.7.dp,
    highlightAlpha = 0.42f,
    fallbackColor = Color(0xE51A1A1E)
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CameraTopSheet(
    visible: Boolean,
    captureMode: CaptureMode,
    aspectRatio: AspectRatio,
    topSheetAspectRatios: List<AspectRatio>,
    onAspectRatioChange: (AspectRatio) -> Unit,
    videoAspectRatio: VideoAspectRatio,
    onVideoAspectRatioChange: (VideoAspectRatio) -> Unit,
    videoLogProfile: VideoLogProfile,
    availableVideoLogProfiles: List<VideoLogProfile>,
    onVideoLogProfileChange: (VideoLogProfile) -> Unit,
    videoBitrate: VideoBitratePreset,
    onVideoBitrateChange: (VideoBitratePreset) -> Unit,
    videoCodec: VideoCodec,
    onVideoCodecChange: (VideoCodec) -> Unit,
    videoHdrEnabled: Boolean,
    onVideoHdrToggle: () -> Unit,
    videoTorchEnabled: Boolean,
    videoTorchSupported: Boolean,
    onVideoTorchToggle: () -> Unit,
    videoStabilizationMode: VideoStabilizationMode,
    availableVideoStabilizationModes: List<VideoStabilizationMode>,
    isVideoRecording: Boolean,
    onVideoStabilizationModeChange: (VideoStabilizationMode) -> Unit,
    onEditClick: () -> Unit,
    onDualCaptureClick: () -> Unit,
    videoAudioInputId: String,
    videoAudioInputOptions: List<VideoAudioInputOption>,
    onVideoAudioInputChange: (String) -> Unit,
    useRaw: Boolean,
    isRawSupported: Boolean,
    rawDcpId: String?,
    rawDcpIdsByLens: Map<String, String?> = emptyMap(),
    rawDcpLensOptions: List<RawDcpLensOption> = emptyList(),
    availableDcps: List<DcpInfo>,
    rawHncsFilmCurveMode: HncsFilmCurveMode,
    rawRenderingEngine: RawRenderingEngine,
    rawToneMappingParameters: RawToneMappingParameters,
    rawSpectralFilmSelection: SpectralFilmSelection?,
    rawSpectralFilmPrint: String?,
    ultraHdrEnabled: Boolean,
    onUltraHdrToggle: (Boolean) -> Unit,
    photoPreviewStabilizationEnabled: Boolean,
    photoPreviewStabilizationAvailable: Boolean,
    onPhotoPreviewStabilizationChange: (Boolean) -> Unit,
    onRawDcpChange: (String?) -> Unit,
    onRawDcpIdsByLensChange: ((Map<String, String?>) -> Unit)? = null,
    onRawHncsFilmCurveModeChange: (HncsFilmCurveMode) -> Unit,
    onImportRawDcp: () -> Unit,
    onDeleteRawDcp: (DcpInfo) -> Unit,
    onRawColorEngineChange: (RawRenderingEngine) -> Unit,
    onRawToneMappingParametersChange: (RawToneMappingParameters) -> Unit,
    onRawSpectralFilmSelectionChange: (SpectralFilmSelection?) -> Unit,
    onRawSpectralFilmPrintChange: (String?) -> Unit,
    meteringMode: MeteringMode,
    onMeteringModeChange: (MeteringMode) -> Unit,
    onFilterManageClick: () -> Unit,
    onFrameManageClick: () -> Unit,
    onPresetManageClick: () -> Unit,
    onToolboxClick: () -> Unit,
    onMoreSettingsClick: () -> Unit,
    useJpgMax: Boolean,
    onJpgMaxToggle: (Boolean) -> Unit,
    useMultipleExposure: Boolean,
    onMultipleExposureToggle: (Boolean) -> Unit,
    oppoPhotoSettings: OppoPhotoSettings,
    onOppoPhotoSettingsChange: (OppoPhotoSettings) -> Unit,
    refocusAvailable: Boolean,
    glassBackdrop: LiquidGlassBackdrop,
    useLivePhoto: Boolean,
    onLivePhotoToggle: () -> Unit,
    timerSeconds: Int,
    onTimerClick: () -> Unit,
    isVirtualApertureEnabled: Boolean,
    onVirtualApertureToggle: () -> Unit,
    onLutClick: () -> Unit,
    contentTopPadding: Dp = CameraTopSheetContentTopPadding,
    modifier: Modifier = Modifier
) {
    var expandedVideoPanel by rememberSaveable { mutableStateOf<VideoSettingPanel?>(null) }
    var showRenderingEngineSheet by rememberSaveable { mutableStateOf(false) }
    var showOppoPipelineSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(visible, captureMode) {
        expandedVideoPanel = null
    }

    LaunchedEffect(useRaw) {
        if (!useRaw) {
            showRenderingEngineSheet = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        // Match OppoCam's six-dot menu: a compact card that unfolds from the top
        // instead of a full-width square sheet.
        enter = fadeIn(animationSpec = tween(170)) +
            scaleIn(
                initialScale = 0.80f,
                animationSpec = spring(dampingRatio = 0.50f, stiffness = 420f)
            ) +
            expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = spring(dampingRatio = 0.66f, stiffness = 360f)
            ),
        exit = fadeOut(animationSpec = tween(130)) +
            scaleOut(
                targetScale = 0.94f,
                animationSpec = tween(130)
            ) +
            shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(160)
            ),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = contentTopPadding, start = 14.dp, end = 14.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .shadow(
                        elevation = 18.dp,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .clip(RoundedCornerShape(24.dp))
            ) {
                LiquidGlassSurface(
                    backdrop = glassBackdrop,
                    modifier = Modifier.matchParentSize(),
                    cornerRadius = 24.dp,
                    style = PhotoQuickMenuGlassStyle
                ) {}
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 600.dp)
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                        .autoRotate()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.42f))
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                if (captureMode == CaptureMode.PHOTO) {
                    PhotoQuickMenuGrid(
                        aspectRatio = aspectRatio,
                        topSheetAspectRatios = topSheetAspectRatios,
                        onAspectRatioChange = onAspectRatioChange,
                        refocusEnabled = oppoPhotoSettings.refocusEnabled,
                        refocusAvailable = refocusAvailable,
                        onRefocusToggle = {
                            val enabled = !oppoPhotoSettings.refocusEnabled
                            onOppoPhotoSettingsChange(
                                oppoPhotoSettings.copy(
                                    refocusEnabled = enabled,
                                    autoHdrEnabled = if (enabled) false else oppoPhotoSettings.autoHdrEnabled
                                )
                            )
                        },
                        useLivePhoto = useLivePhoto,
                        onLivePhotoToggle = onLivePhotoToggle,
                        timerSeconds = timerSeconds,
                        onTimerClick = onTimerClick,
                        burstEnabled = oppoPhotoSettings.fastShutterEnabled,
                        burstAvailable = !(useRaw && isRawSupported),
                        onBurstToggle = {
                            onOppoPhotoSettingsChange(
                                oppoPhotoSettings.copy(fastShutterEnabled = !oppoPhotoSettings.fastShutterEnabled)
                            )
                        },
                        stylesEnabled = oppoPhotoSettings.customPipelineEnabled,
                        onStylesClick = { showOppoPipelineSheet = true },
                        isVirtualApertureEnabled = isVirtualApertureEnabled,
                        onVirtualApertureToggle = onVirtualApertureToggle,
                        onFilterClick = onLutClick,
                        onSettingsClick = onMoreSettingsClick
                    )
                } else {
                    VideoQuickMenuGrid(
                        videoTorchEnabled = videoTorchEnabled,
                        videoTorchSupported = videoTorchSupported,
                        isVideoRecording = isVideoRecording,
                        onVideoTorchToggle = onVideoTorchToggle,
                        videoStabilizationMode = videoStabilizationMode,
                        availableVideoStabilizationModes = availableVideoStabilizationModes,
                        onVideoStabilizationModeChange = onVideoStabilizationModeChange,
                        onEditClick = onEditClick,
                        onDualCaptureClick = onDualCaptureClick,
                        videoHdrEnabled = videoHdrEnabled,
                        onFormatClick = {
                            expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.FORMAT) null else VideoSettingPanel.FORMAT
                        },
                        videoBitrate = videoBitrate,
                        onBitrateClick = {
                            expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.BITRATE) null else VideoSettingPanel.BITRATE
                        },
                        videoLogProfile = videoLogProfile,
                        onLogClick = {
                            expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.LOG_PROFILE) null else VideoSettingPanel.LOG_PROFILE
                        },
                        videoAspectRatio = videoAspectRatio,
                        onAspectRatioClick = {
                            expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.ASPECT_RATIO) null else VideoSettingPanel.ASPECT_RATIO
                        },
                        onSettingsClick = onMoreSettingsClick
                    )

                    AnimatedVisibility(
                        visible = expandedVideoPanel != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            VideoSettingExpandedPanel {
                                when (expandedVideoPanel) {
                                    VideoSettingPanel.ASPECT_RATIO -> {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            VideoAspectRatio.entries.forEach { ratio ->
                                                VideoOptionChip(
                                                    title = videoAspectRatioLabel(ratio),
                                                    selected = videoAspectRatio == ratio,
                                                    onClick = { onVideoAspectRatioChange(ratio) }
                                                )
                                            }
                                        }
                                        if (videoAspectRatio == VideoAspectRatio.OPEN_GATE) {
                                            Text(
                                                text = stringResource(R.string.video_open_gate_resolution_description),
                                                color = Color.White.copy(alpha = 0.6f),
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(top = 8.dp)
                                            )
                                        }
                                    }

                                    VideoSettingPanel.LOG_PROFILE -> {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            availableVideoLogProfiles.forEach { profile ->
                                                VideoOptionChip(
                                                    title = videoLogProfileLabel(profile),
                                                    selected = videoLogProfile == profile,
                                                    onClick = { onVideoLogProfileChange(profile) }
                                                )
                                            }
                                        }
                                    }

                                    VideoSettingPanel.BITRATE -> {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            VideoBitratePreset.entries.forEach { bitrate ->
                                                VideoOptionChip(
                                                    title = "${bitrate.bitrateMbps}M",
                                                    selected = videoBitrate == bitrate,
                                                    onClick = { onVideoBitrateChange(bitrate) }
                                                )
                                            }
                                        }
                                    }

                                    VideoSettingPanel.FORMAT -> {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            VideoOptionChip(
                                                title = stringResource(R.string.hdr_label),
                                                selected = videoHdrEnabled,
                                                onClick = {
                                                    if (!videoLogProfile.isEnabled) onVideoHdrToggle()
                                                }
                                            )
                                            if (videoLogProfile.isEnabled) {
                                                Text(
                                                    text = stringResource(R.string.video_hdr_disabled_log),
                                                    color = Color.White.copy(alpha = 0.58f),
                                                    fontSize = 12.sp
                                                )
                                            }
                                            SectionLabel(title = stringResource(R.string.video_codec_chip))
                                            FlowRow(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                VideoCodec.entries.forEach { codec ->
                                                    VideoOptionChip(
                                                        title = codec.displayName,
                                                        selected = videoCodec == codec,
                                                        onClick = { onVideoCodecChange(codec) }
                                                    )
                                                }
                                            }
                                            SectionLabel(title = stringResource(R.string.video_microphone_title))
                                            FlowRow(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                VideoOptionChip(
                                                    title = stringResource(R.string.video_microphone_auto),
                                                    selected = videoAudioInputId == VIDEO_AUDIO_INPUT_AUTO,
                                                    onClick = { onVideoAudioInputChange(VIDEO_AUDIO_INPUT_AUTO) }
                                                )
                                                videoAudioInputOptions.forEach { option ->
                                                    VideoOptionChip(
                                                        title = videoAudioInputLabel(option),
                                                        selected = videoAudioInputId == option.id,
                                                        onClick = { onVideoAudioInputChange(option.id) }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    VideoSettingPanel.CODEC -> {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            VideoCodec.entries.forEach { codec ->
                                                VideoOptionChip(
                                                    title = codec.displayName,
                                                    selected = videoCodec == codec,
                                                    onClick = { onVideoCodecChange(codec) }
                                                )
                                            }
                                        }
                                    }

                                    VideoSettingPanel.MICROPHONE -> {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            VideoOptionChip(
                                                title = stringResource(R.string.video_microphone_auto),
                                                selected = videoAudioInputId == VIDEO_AUDIO_INPUT_AUTO,
                                                onClick = { onVideoAudioInputChange(VIDEO_AUDIO_INPUT_AUTO) }
                                            )
                                            videoAudioInputOptions.forEach { option ->
                                                VideoOptionChip(
                                                    title = videoAudioInputLabel(option),
                                                    selected = videoAudioInputId == option.id,
                                                    onClick = { onVideoAudioInputChange(option.id) }
                                                )
                                            }
                                        }
                                    }

                                    null -> Unit
                                }
                            }
                        }
                    }
                }

            }
        }
    }
    }
    }

    if (showOppoPipelineSheet) {
        OppoPhotoPipelineSheet(
            settings = oppoPhotoSettings,
            onSettingsChange = onOppoPhotoSettingsChange,
            onDismiss = { showOppoPipelineSheet = false },
        )
    }

    if (showRenderingEngineSheet) {
        AppModalBottomSheet(
            onDismissRequest = { showRenderingEngineSheet = false },
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.2f)) }
        ) {
            Text(
                text = stringResource(R.string.settings_raw_color_engine),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            // Fill the sheet so switching engines only changes the scroll range, never the anchors.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 8.dp)
            ) {
                RawRenderingEngineSettingsPanel(
                    selectedDcpId = rawDcpId,
                    rawDcpIdsByLens = rawDcpIdsByLens,
                    dcpLensOptions = rawDcpLensOptions,
                    availableDcps = availableDcps,
                    rawRenderingEngine = rawRenderingEngine,
                    rawToneMappingParameters = rawToneMappingParameters,
                    spectralFilmSelection = rawSpectralFilmSelection,
                    spectralFilmPrint = rawSpectralFilmPrint,
                    onSelectDcp = onRawDcpChange,
                    onRawDcpIdsByLensChange = onRawDcpIdsByLensChange,
                    onImportDcp = onImportRawDcp,
                    onDeleteDcp = onDeleteRawDcp,
                    hncsFilmCurveMode = rawHncsFilmCurveMode,
                    onHncsFilmCurveModeChange = onRawHncsFilmCurveModeChange,
                    onRawColorEngineChange = onRawColorEngineChange,
                    onRawToneMappingParametersChange = onRawToneMappingParametersChange,
                    onSpectralFilmSelectionChange = onRawSpectralFilmSelectionChange,
                    onSpectralFilmPrintChange = onRawSpectralFilmPrintChange,
                    inlineEngineOptions = true,
                    showToneMappingControls = true,
                )
            }
        }
    }
}



private enum class OppoQuickMenuGlyph {
    REFOCUS,
    LIVE,
    ASPECT,
    TIMER,
    BURST,
    STYLES,
    FILTER,
    DEPTH,
    SETTINGS,
    FLASH,
    EDIT,
    ACTION,
    DUAL_CAPTURE,
    LOG
}

/**
 * Oppo-style quick-menu glyphs drawn as clean, rounded vector strokes.
 * Drawing them here keeps the icon family optically consistent at every density.
 */
@Composable
private fun OppoQuickMenuGlyphIcon(
    glyph: OppoQuickMenuGlyph,
    ratioLabel: String? = null,
    modifier: Modifier = Modifier.size(40.dp)
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val unit = minOf(size.width, size.height) / 40f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val white = Color.White
            val stroke = Stroke(
                width = 2.8f * unit,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
            fun point(x: Float, y: Float) = Offset(cx + x * unit, cy + y * unit)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 2.8f) {
                drawLine(
                    color = white,
                    start = point(x1, y1),
                    end = point(x2, y2),
                    strokeWidth = width * unit,
                    cap = StrokeCap.Round
                )
            }

            when (glyph) {
                OppoQuickMenuGlyph.REFOCUS -> {
                    val path = Path().apply {
                        moveTo(point(-13f, -6f).x, point(-13f, -6f).y)
                        lineTo(point(-13f, -11f).x, point(-13f, -11f).y)
                        quadraticTo(point(-13f, -14f).x, point(-13f, -14f).y, point(-10f, -14f).x, point(-10f, -14f).y)
                        lineTo(point(-6f, -14f).x, point(-6f, -14f).y)
                        moveTo(point(6f, -14f).x, point(6f, -14f).y)
                        lineTo(point(10f, -14f).x, point(10f, -14f).y)
                        quadraticTo(point(13f, -14f).x, point(13f, -14f).y, point(13f, -11f).x, point(13f, -11f).y)
                        lineTo(point(13f, -6f).x, point(13f, -6f).y)
                        moveTo(point(13f, 6f).x, point(13f, 6f).y)
                        lineTo(point(13f, 11f).x, point(13f, 11f).y)
                        quadraticTo(point(13f, 14f).x, point(13f, 14f).y, point(10f, 14f).x, point(10f, 14f).y)
                        lineTo(point(6f, 14f).x, point(6f, 14f).y)
                        moveTo(point(-6f, 14f).x, point(-6f, 14f).y)
                        lineTo(point(-10f, 14f).x, point(-10f, 14f).y)
                        quadraticTo(point(-13f, 14f).x, point(-13f, 14f).y, point(-13f, 11f).x, point(-13f, 11f).y)
                        lineTo(point(-13f, 6f).x, point(-13f, 6f).y)
                    }
                    drawPath(path, white, style = stroke)
                    drawCircle(white, radius = 3.1f * unit, center = point(0f, 0f))
                }

                OppoQuickMenuGlyph.LIVE -> {
                    drawCircle(white, 16.2f * unit, point(0f, 0f), style = stroke)
                    drawCircle(white, 10.4f * unit, point(0f, 0f), style = stroke)
                    drawCircle(white, 4.5f * unit, point(0f, 0f), style = stroke)
                    drawCircle(white, 1.8f * unit, point(0f, 0f))
                }

                OppoQuickMenuGlyph.ASPECT -> {
                    val path = Path().apply {
                        moveTo(point(-15f, -7f).x, point(-15f, -7f).y)
                        lineTo(point(-15f, -12f).x, point(-15f, -12f).y)
                        quadraticTo(point(-15f, -15f).x, point(-15f, -15f).y, point(-12f, -15f).x, point(-12f, -15f).y)
                        lineTo(point(-7f, -15f).x, point(-7f, -15f).y)
                        moveTo(point(7f, -15f).x, point(7f, -15f).y)
                        lineTo(point(12f, -15f).x, point(12f, -15f).y)
                        quadraticTo(point(15f, -15f).x, point(15f, -15f).y, point(15f, -12f).x, point(15f, -12f).y)
                        lineTo(point(15f, -7f).x, point(15f, -7f).y)
                        moveTo(point(15f, 7f).x, point(15f, 7f).y)
                        lineTo(point(15f, 12f).x, point(15f, 12f).y)
                        quadraticTo(point(15f, 15f).x, point(15f, 15f).y, point(12f, 15f).x, point(12f, 15f).y)
                        lineTo(point(7f, 15f).x, point(7f, 15f).y)
                        moveTo(point(-7f, 15f).x, point(-7f, 15f).y)
                        lineTo(point(-12f, 15f).x, point(-12f, 15f).y)
                        quadraticTo(point(-15f, 15f).x, point(-15f, 15f).y, point(-15f, 12f).x, point(-15f, 12f).y)
                        lineTo(point(-15f, 7f).x, point(-15f, 7f).y)
                    }
                    drawPath(path, white, style = stroke)
                }

                OppoQuickMenuGlyph.TIMER -> {
                    drawArc(
                        color = white,
                        startAngle = 43f,
                        sweepAngle = 292f,
                        useCenter = false,
                        topLeft = point(-14f, -14f),
                        size = Size(28f * unit, 28f * unit),
                        style = stroke
                    )
                    line(-16f, -16f, 16f, 16f, 3.3f)
                }

                OppoQuickMenuGlyph.BURST -> {
                    drawRoundRect(white, point(-4f, -17f), Size(20f * unit, 17f * unit), CornerRadius(3f * unit), style = stroke)
                    drawRoundRect(white, point(-9f, -13f), Size(20f * unit, 17f * unit), CornerRadius(3f * unit), style = stroke)
                    drawRoundRect(white, point(-14f, -9f), Size(20f * unit, 17f * unit), CornerRadius(3f * unit), style = stroke)
                }

                OppoQuickMenuGlyph.STYLES -> {
                    fun card(dx: Float, dy: Float) {
                        val path = Path().apply {
                            moveTo(point(-11f + dx, -8f + dy).x, point(-11f + dx, -8f + dy).y)
                            lineTo(point(1f + dx, -16f + dy).x, point(1f + dx, -16f + dy).y)
                            lineTo(point(14f + dx, -8f + dy).x, point(14f + dx, -8f + dy).y)
                            lineTo(point(14f + dx, 10f + dy).x, point(14f + dx, 10f + dy).y)
                            lineTo(point(1f + dx, 18f + dy).x, point(1f + dx, 18f + dy).y)
                            lineTo(point(-11f + dx, 10f + dy).x, point(-11f + dx, 10f + dy).y)
                            close()
                        }
                        drawPath(path, white, style = stroke)
                    }
                    card(6f, -5f)
                    card(0f, 0f)
                    card(-6f, 5f)
                }

                OppoQuickMenuGlyph.FILTER -> {
                    drawCircle(white.copy(alpha = 0.55f), 8.7f * unit, point(-5f, 3f), style = Stroke(2.8f * unit, cap = StrokeCap.Round))
                    drawCircle(white, 8.7f * unit, point(5f, 3f), style = Stroke(2.8f * unit, cap = StrokeCap.Round))
                    drawCircle(white, 8.7f * unit, point(0f, -5f), style = Stroke(2.8f * unit, cap = StrokeCap.Round))
                }

                OppoQuickMenuGlyph.DEPTH -> {
                    val glyphPath = Path().apply {
                        moveTo(point(-8f, 13f).x, point(-8f, 13f).y)
                        cubicTo(
                            point(-2f, 13f).x, point(-2f, 13f).y,
                            point(-2f, 8f).x, point(-2f, 8f).y,
                            point(-2f, 2f).x, point(-2f, 2f).y
                        )
                        lineTo(point(-2f, -5f).x, point(-2f, -5f).y)
                        cubicTo(
                            point(-2f, -13f).x, point(-2f, -13f).y,
                            point(3f, -15f).x, point(3f, -15f).y,
                            point(10f, -15f).x, point(10f, -15f).y
                        )
                        moveTo(point(-8f, 0f).x, point(-8f, 0f).y)
                        lineTo(point(7f, 0f).x, point(7f, 0f).y)
                    }
                    drawPath(glyphPath, white, style = Stroke(3.2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    line(-16f, -15f, 15f, 16f, 3.4f)
                }

                OppoQuickMenuGlyph.SETTINGS -> {
                    val gear = Path()
                    for (index in 0 until 32) {
                        val angle = -Math.PI / 2.0 + index * (Math.PI * 2.0 / 32.0)
                        val radius = if (index % 4 < 2) 16.5 else 20.0
                        val x = cx + kotlin.math.cos(angle).toFloat() * radius.toFloat() * unit
                        val y = cy + kotlin.math.sin(angle).toFloat() * radius.toFloat() * unit
                        if (index == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                    }
                    gear.close()
                    drawPath(gear, white, style = stroke)
                    drawCircle(white, 5.2f * unit, point(0f, 0f), style = stroke)
                }

                OppoQuickMenuGlyph.FLASH -> {
                    val bolt = Path().apply {
                        moveTo(point(2f, -18f).x, point(2f, -18f).y)
                        lineTo(point(-10f, 2f).x, point(-10f, 2f).y)
                        lineTo(point(-1f, 2f).x, point(-1f, 2f).y)
                        lineTo(point(-4f, 18f).x, point(-4f, 18f).y)
                        lineTo(point(11f, -4f).x, point(11f, -4f).y)
                        lineTo(point(2f, -4f).x, point(2f, -4f).y)
                        close()
                    }
                    drawPath(bolt, white, style = Stroke(2.8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }

                OppoQuickMenuGlyph.EDIT -> {
                    line(-15f, -10f, 15f, -10f, 2.5f)
                    line(-15f, 0f, 15f, 0f, 2.5f)
                    line(-15f, 10f, 15f, 10f, 2.5f)
                    drawCircle(white, 3.6f * unit, point(-5f, -10f))
                    drawCircle(white, 3.6f * unit, point(7f, 0f))
                    drawCircle(white, 3.6f * unit, point(-1f, 10f))
                }

                OppoQuickMenuGlyph.ACTION -> {
                    drawCircle(white, 6f * unit, point(0f, 0f), style = stroke)
                    drawArc(white, 205f, 125f, false, point(-17f, -17f), Size(34f * unit, 34f * unit), style = stroke)
                    drawArc(white, 25f, 125f, false, point(-17f, -17f), Size(34f * unit, 34f * unit), style = stroke)
                }

                OppoQuickMenuGlyph.DUAL_CAPTURE -> {
                    drawRoundRect(white, point(-6f, -16f), Size(21f * unit, 27f * unit), CornerRadius(2.5f * unit), style = stroke)
                    drawRoundRect(white, point(-14f, -9f), Size(21f * unit, 25f * unit), CornerRadius(2.5f * unit), style = stroke)
                }

                OppoQuickMenuGlyph.LOG -> {
                    val eye = Path().apply {
                        moveTo(point(-17f, 0f).x, point(-17f, 0f).y)
                        cubicTo(
                            point(-9f, -11f).x, point(-9f, -11f).y,
                            point(9f, -11f).x, point(9f, -11f).y,
                            point(17f, 0f).x, point(17f, 0f).y
                        )
                        cubicTo(
                            point(9f, 11f).x, point(9f, 11f).y,
                            point(-9f, 11f).x, point(-9f, 11f).y,
                            point(-17f, 0f).x, point(-17f, 0f).y
                        )
                        close()
                    }
                    drawPath(eye, white, style = stroke)
                    drawCircle(white, 4.3f * unit, point(0f, 0f), style = stroke)
                }
            }
        }

        if (glyph == OppoQuickMenuGlyph.ASPECT && !ratioLabel.isNullOrBlank()) {
            Text(
                text = ratioLabel,
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.35).sp
            )
        }
    }
}

@Composable
private fun PhotoQuickMenuGrid(
    aspectRatio: AspectRatio,
    topSheetAspectRatios: List<AspectRatio>,
    onAspectRatioChange: (AspectRatio) -> Unit,
    refocusEnabled: Boolean,
    refocusAvailable: Boolean,
    onRefocusToggle: () -> Unit,
    useLivePhoto: Boolean,
    onLivePhotoToggle: () -> Unit,
    timerSeconds: Int,
    onTimerClick: () -> Unit,
    burstEnabled: Boolean,
    burstAvailable: Boolean,
    onBurstToggle: () -> Unit,
    stylesEnabled: Boolean,
    onStylesClick: () -> Unit,
    isVirtualApertureEnabled: Boolean,
    onVirtualApertureToggle: () -> Unit,
    onFilterClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val availableRatios = AspectRatio.sanitizeTopSheetRatios(topSheetAspectRatios)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            PhotoQuickMenuItem(
                title = stringResource(R.string.oppo_refocus).uppercase(),
                checked = refocusEnabled,
                enabled = refocusAvailable,
                onClick = onRefocusToggle,
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.REFOCUS, modifier = Modifier.size(42.dp))
            }
            PhotoQuickMenuItem(
                title = stringResource(R.string.camera_quick_menu_live).uppercase(),
                checked = useLivePhoto,
                onClick = onLivePhotoToggle,
                modifier = Modifier.weight(1f)
            ) {
                Box {
                    OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.LIVE, modifier = Modifier.size(42.dp))
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 3.dp, y = 3.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF17171D))
                            .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("A", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            PhotoQuickMenuItem(
                title = stringResource(R.string.camera_quick_menu_aspect).uppercase(),
                checked = false,
                onClick = {
                    if (availableRatios.isNotEmpty()) {
                        val nextIndex = (availableRatios.indexOf(aspectRatio) + 1)
                            .coerceAtLeast(0) % availableRatios.size
                        onAspectRatioChange(availableRatios[nextIndex])
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(
                    OppoQuickMenuGlyph.ASPECT,
                    ratioLabel = aspectRatio.getDisplayName(),
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            PhotoQuickMenuItem(
                title = stringResource(R.string.timer).uppercase(),
                checked = timerSeconds > 0,
                onClick = onTimerClick,
                modifier = Modifier.weight(1f)
            ) {
                Box {
                    OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.TIMER, modifier = Modifier.size(42.dp))
                    if (timerSeconds > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 5.dp, y = (-4).dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(AccentColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = timerSeconds.toString(),
                                color = OnAccentColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            PhotoQuickMenuItem(
                title = stringResource(R.string.camera_quick_menu_burst).uppercase(),
                checked = burstEnabled,
                enabled = burstAvailable,
                onClick = onBurstToggle,
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.BURST, modifier = Modifier.size(42.dp))
            }
            PhotoQuickMenuItem(
                title = stringResource(R.string.camera_quick_menu_styles).uppercase(),
                checked = stylesEnabled,
                onClick = onStylesClick,
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.STYLES, modifier = Modifier.size(42.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            PhotoQuickMenuItem(
                title = stringResource(R.string.camera_quick_menu_filter).uppercase(),
                checked = false,
                onClick = onFilterClick,
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.FILTER, modifier = Modifier.size(42.dp))
            }
            PhotoQuickMenuItem(
                title = stringResource(R.string.camera_quick_menu_depth).uppercase(),
                checked = isVirtualApertureEnabled,
                onClick = onVirtualApertureToggle,
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.DEPTH, modifier = Modifier.size(42.dp))
            }
            PhotoQuickMenuItem(
                title = stringResource(R.string.settings).uppercase(),
                checked = false,
                onClick = onSettingsClick,
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.SETTINGS, modifier = Modifier.size(42.dp))
            }
        }
    }
}

@Composable
private fun VideoQuickMenuGrid(
    videoTorchEnabled: Boolean,
    videoTorchSupported: Boolean,
    isVideoRecording: Boolean,
    onVideoTorchToggle: () -> Unit,
    videoStabilizationMode: VideoStabilizationMode,
    availableVideoStabilizationModes: List<VideoStabilizationMode>,
    onVideoStabilizationModeChange: (VideoStabilizationMode) -> Unit,
    onEditClick: () -> Unit,
    onDualCaptureClick: () -> Unit,
    videoHdrEnabled: Boolean,
    onFormatClick: () -> Unit,
    videoBitrate: VideoBitratePreset,
    onBitrateClick: () -> Unit,
    videoLogProfile: VideoLogProfile,
    onLogClick: () -> Unit,
    videoAspectRatio: VideoAspectRatio,
    onAspectRatioClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val enhancedAvailable = VideoStabilizationMode.ENHANCED in availableVideoStabilizationModes
    val fallbackStabilizationMode = availableVideoStabilizationModes.firstOrNull {
        it == VideoStabilizationMode.EIS
    } ?: availableVideoStabilizationModes.firstOrNull {
        it != VideoStabilizationMode.ENHANCED
    } ?: VideoStabilizationMode.OFF
    var previousStabilizationMode by remember {
        mutableStateOf(
            videoStabilizationMode.takeIf { it != VideoStabilizationMode.ENHANCED }
                ?: fallbackStabilizationMode
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            PhotoQuickMenuItem(
                title = stringResource(R.string.flash).uppercase(),
                checked = videoTorchEnabled,
                enabled = videoTorchSupported && !isVideoRecording,
                onClick = onVideoTorchToggle,
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.FLASH, modifier = Modifier.size(42.dp))
            }
            PhotoQuickMenuItem(
                title = stringResource(R.string.edit).uppercase(),
                checked = false,
                onClick = onEditClick,
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.EDIT, modifier = Modifier.size(42.dp))
            }
            PhotoQuickMenuItem(
                title = stringResource(R.string.video_quick_menu_action).uppercase(),
                checked = videoStabilizationMode == VideoStabilizationMode.ENHANCED,
                enabled = enhancedAvailable && !isVideoRecording,
                onClick = {
                    val nextMode = if (videoStabilizationMode == VideoStabilizationMode.ENHANCED) {
                        previousStabilizationMode.takeIf {
                            it in availableVideoStabilizationModes
                        } ?: fallbackStabilizationMode
                    } else {
                        previousStabilizationMode = videoStabilizationMode
                        VideoStabilizationMode.ENHANCED
                    }
                    onVideoStabilizationModeChange(nextMode)
                },
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.ACTION, modifier = Modifier.size(42.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            PhotoQuickMenuItem(
                title = stringResource(R.string.video_quick_menu_dual_capture).uppercase(),
                checked = false,
                onClick = onDualCaptureClick,
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.DUAL_CAPTURE, modifier = Modifier.size(42.dp))
            }
            PhotoQuickMenuItem(
                title = stringResource(R.string.video_quick_menu_format).uppercase(),
                checked = videoHdrEnabled,
                onClick = onFormatClick,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.hdr_label),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            PhotoQuickMenuItem(
                title = stringResource(R.string.video_bitrate_chip).uppercase(),
                checked = false,
                onClick = onBitrateClick,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = videoBitrate.bitrateMbps.toString(),
                    color = Color.White,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            PhotoQuickMenuItem(
                title = stringResource(R.string.video_log_chip).uppercase(),
                checked = videoLogProfile.isEnabled,
                onClick = onLogClick,
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.LOG, modifier = Modifier.size(42.dp))
            }
            PhotoQuickMenuItem(
                title = stringResource(R.string.video_quick_menu_aspect_ratio).uppercase(),
                checked = false,
                onClick = onAspectRatioClick,
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(
                    OppoQuickMenuGlyph.ASPECT,
                    ratioLabel = videoAspectRatioLabel(videoAspectRatio),
                    modifier = Modifier.size(44.dp)
                )
            }
            PhotoQuickMenuItem(
                title = stringResource(R.string.settings).uppercase(),
                checked = false,
                onClick = onSettingsClick,
                modifier = Modifier.weight(1f)
            ) {
                OppoQuickMenuGlyphIcon(OppoQuickMenuGlyph.SETTINGS, modifier = Modifier.size(42.dp))
            }
        }
    }
}

@Composable
private fun PhotoQuickMenuItem(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable BoxScope.() -> Unit
) {
    Column(
        modifier = modifier
            .height(128.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(72.dp)
                .clip(CircleShape)
                .background(Color(0xEB090A10))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = if (checked) 0.30f else 0.11f),
                    shape = CircleShape
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Text(
            text = title,
            color = Color.White.copy(alpha = if (enabled) 0.98f else 0.45f),
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.35.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 7.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun OppoPhotoPipelineSheet(
    settings: OppoPhotoSettings,
    onSettingsChange: (OppoPhotoSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.2f)) }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.oppo_custom_pipeline),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            QuickSettingToggle(
                title = stringResource(R.string.oppo_enable_custom_pipeline),
                checked = settings.customPipelineEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(customPipelineEnabled = it)) },
                modifier = Modifier.fillMaxWidth(),
            )
            SectionLabel(title = stringResource(R.string.oppo_color_profile))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "NATURAL" to R.string.oppo_profile_natural,
                    "HASSELBLAD" to R.string.oppo_profile_hasselblad,
                    "SAMSUNG" to R.string.oppo_profile_samsung,
                    "PIXEL" to R.string.oppo_profile_pixel,
                    "IPHONE" to R.string.oppo_profile_iphone,
                    "CUSTOM" to R.string.oppo_profile_custom,
                ).forEach { (profile, label) ->
                    VideoOptionChip(
                        title = stringResource(label),
                        selected = settings.pipelineProfile == profile,
                        onClick = {
                            onSettingsChange(settings.copy(
                                pipelineProfile = profile,
                                customPipelineEnabled = true,
                            ))
                        },
                    )
                }
            }
            OppoPipelineSlider(R.string.oppo_pipeline_exposure, settings.exposure, -2f..2f) {
                onSettingsChange(settings.copy(exposure = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_contrast, settings.contrast, 0.6f..1.4f) {
                onSettingsChange(settings.copy(contrast = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_highlights, settings.highlights, -100f..100f) {
                onSettingsChange(settings.copy(highlights = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_shadows, settings.shadows, -100f..100f) {
                onSettingsChange(settings.copy(shadows = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_saturation, settings.saturation, -100f..100f) {
                onSettingsChange(settings.copy(saturation = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_vibrance, settings.vibrance, -100f..100f) {
                onSettingsChange(settings.copy(vibrance = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_temperature, settings.temperature, -100f..100f) {
                onSettingsChange(settings.copy(temperature = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_tint, settings.tint, -100f..100f) {
                onSettingsChange(settings.copy(tint = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_sharpness, settings.sharpness, 0f..100f) {
                onSettingsChange(settings.copy(sharpness = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_texture, settings.texture, -100f..100f) {
                onSettingsChange(settings.copy(texture = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_noise_reduction, settings.noiseReduction, 0f..100f) {
                onSettingsChange(settings.copy(noiseReduction = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_detail_preservation, settings.detailPreservation, 0f..100f) {
                onSettingsChange(settings.copy(detailPreservation = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
        }
    }
}

@Composable
private fun OppoPipelineSlider(
    titleRes: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(titleRes),
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = String.format("%.2f", value),
                color = AccentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun VideoSettingTile(
    title: String,
    summary: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (expanded) AccentColor else Color.White.copy(alpha = 0.14f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = title,
            color = if (expanded) OnAccentColor.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.72f),
            fontSize = 9.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickSettingLabel(
                text = summary,
                color = if (expanded) OnAccentColor else Color.White,
                maxFontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.OpenInFull,
                contentDescription = null,
                tint = if (expanded) OnAccentColor else Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun VideoSettingExpandedPanel(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun VideoOptionChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) AccentColor else Color.White.copy(alpha = 0.12f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (selected) OnAccentColor else Color.White,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun selectedVideoAudioInputLabel(
    selectedAudioInputId: String,
    options: List<VideoAudioInputOption>
): String {
    if (selectedAudioInputId == VIDEO_AUDIO_INPUT_AUTO) {
        return stringResource(R.string.video_microphone_auto)
    }
    val option = options.firstOrNull { it.id == selectedAudioInputId }
    return option?.let { videoAudioInputLabel(it) } ?: stringResource(R.string.video_microphone_disconnected)
}

@Composable
private fun videoAudioInputLabel(option: VideoAudioInputOption): String {
    val baseLabel = when (option.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> stringResource(R.string.video_microphone_builtin)
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> stringResource(R.string.video_microphone_wired)
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> stringResource(R.string.video_microphone_bluetooth)
        AudioDeviceInfo.TYPE_BLE_HEADSET -> stringResource(R.string.video_microphone_ble)
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> stringResource(R.string.video_microphone_usb)
        AudioDeviceInfo.TYPE_HDMI -> stringResource(R.string.video_microphone_hdmi)
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> stringResource(R.string.video_microphone_line_in)
        else -> stringResource(R.string.video_microphone_external)
    }
    val customName = option.productName
        ?.takeUnless { it.equals(Build.MODEL, ignoreCase = true) }
        ?.takeUnless { it.equals(Build.DEVICE, ignoreCase = true) }
        ?.takeUnless { it.equals(Build.PRODUCT, ignoreCase = true) }
    val suffix = when {
        option.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && !option.address.isNullOrBlank() -> option.address
        customName != null && customName != baseLabel -> customName
        else -> null
    }
    return if (suffix != null) "$baseLabel (${suffix})" else baseLabel
}

@Composable
private fun videoAspectRatioLabel(aspectRatio: VideoAspectRatio): String {
    return when (aspectRatio) {
        VideoAspectRatio.RATIO_16_9 -> stringResource(R.string.video_aspect_16_9)
        VideoAspectRatio.RATIO_21_9 -> stringResource(R.string.video_aspect_21_9)
        VideoAspectRatio.OPEN_GATE -> stringResource(R.string.video_aspect_open_gate)
    }
}

@Composable
private fun videoLogProfileLabel(profile: VideoLogProfile): String {
    return when (profile) {
        VideoLogProfile.OFF -> stringResource(R.string.video_log_off)
        else -> profile.displayName
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun MeteringModeQuickSetting(
    meteringMode: MeteringMode,
    onMeteringModeChange: (MeteringMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val meteringLabel = when (meteringMode) {
        MeteringMode.SPOT -> stringResource(R.string.metering_spot)
        MeteringMode.CENTER_WEIGHTED -> stringResource(R.string.metering_center_weighted)
        MeteringMode.SYSTEM_DEFAULT -> stringResource(R.string.metering_system_default)
        MeteringMode.AVERAGE -> stringResource(R.string.metering_average)
        MeteringMode.HIGHLIGHT_PRIORITY -> stringResource(R.string.metering_highlight_priority)
    }
    QuickSettingValue(
        title = stringResource(R.string.metering_mode),
        value = meteringLabel,
        onClick = {
            val next = when (meteringMode) {
                MeteringMode.SPOT -> MeteringMode.SYSTEM_DEFAULT
                MeteringMode.SYSTEM_DEFAULT -> MeteringMode.CENTER_WEIGHTED
                MeteringMode.CENTER_WEIGHTED -> MeteringMode.AVERAGE
                MeteringMode.AVERAGE -> MeteringMode.HIGHLIGHT_PRIORITY
                MeteringMode.HIGHLIGHT_PRIORITY -> MeteringMode.SPOT
            }
            onMeteringModeChange(next)
        },
        modifier = modifier
    )
}

@Composable
private fun ToolboxQuickSetting(
    onToolboxClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    QuickSettingButton(
        title = stringResource(R.string.toolbox_title),
        icon = AppIcons.Palette,
        onClick = onToolboxClick,
        modifier = modifier
    )
}

@Composable
private fun ContentManagementQuickSetting(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    QuickSettingButton(
        title = stringResource(R.string.settings_section_management),
        icon = AppIcons.Tune,
        onClick = onClick,
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickSettingGrid(
    columns: Int = 3,
    itemHeight: Dp = 48.dp,
    content: @Composable (Modifier) -> Unit
) {
    // Keep a consistent column count as the available photo controls change.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = columns
    ) {
        content(Modifier.weight(1f).height(itemHeight))
    }
}

@Composable
private fun QuickSettingLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    maxFontSize: TextUnit = 10.sp
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = LocalTextStyle.current.copy(
            color = color,
            fontWeight = fontWeight,
            fontSize = maxFontSize,
            lineHeight = 1.2.em
        ),
        maxLines = 2,
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = maxFontSize)
    )
}

@Composable
private fun ContentManagementOptionsPanel(
    visible: Boolean,
    onFilterManageClick: () -> Unit,
    onFrameManageClick: () -> Unit,
    onPresetManageClick: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Column {
            Spacer(modifier = Modifier.height(12.dp))
            QuickSettingGrid { itemModifier ->
                QuickSettingButton(
                    title = stringResource(R.string.settings_filter_management),
                    icon = AppIcons.AutoAwesome,
                    onClick = onFilterManageClick,
                    modifier = itemModifier
                )
                QuickSettingButton(
                    title = stringResource(R.string.settings_frame_management),
                    icon = AppIcons.BorderBottom,
                    onClick = onFrameManageClick,
                    modifier = itemModifier
                )
                QuickSettingButton(
                    title = stringResource(R.string.settings_preset_management),
                    icon = AppIcons.Bookmark,
                    onClick = onPresetManageClick,
                    modifier = itemModifier
                )
            }
        }
    }
}

@Composable
fun QuickSettingValue(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.15f))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 8.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
            )
            QuickSettingLabel(
                text = value,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun QuickSettingButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.15f))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickSettingLabel(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}



@Composable
fun QuickSettingButton2(
    title: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (checked) AccentColor else Color.White.copy(
                    alpha = 0.15f
                )
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickSettingLabel(
                text = title,
                color = if (checked) OnAccentColor else Color.White.copy(alpha = 0.9f),
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = if (checked) OnAccentColor else Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun QuickSettingToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (checked) AccentColor.copy(alpha = contentAlpha) else Color.White.copy(
                    alpha = 0.15f
                )
            )
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickSettingLabel(
                text = title,
                color = if (checked) {
                    OnAccentColor.copy(alpha = contentAlpha)
                } else {
                    Color.White.copy(alpha = 0.9f * contentAlpha)
                },
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )

            // Simple indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        if (checked) {
                            OnAccentColor.copy(alpha = contentAlpha)
                        } else {
                            Color.White.copy(alpha = 0.2f * contentAlpha)
                        }
                    )
            )
        }
    }
}
