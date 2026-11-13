package com.hinnka.mycamera.ui.camera

import android.media.AudioDeviceInfo
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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

private enum class VideoSettingPanel {
    ASPECT_RATIO,
    LOG_PROFILE,
    BITRATE,
    CODEC,
    MICROPHONE
}

private val CameraTopSheetContentTopPadding = 32.dp

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
    useLivePhoto: Boolean,
    onLivePhotoToggle: () -> Unit,
    timerSeconds: Int,
    onTimerToggle: () -> Unit,
    onPhotoFilterClick: () -> Unit,
    onPhotoDepthClick: () -> Unit,
    videoTorchEnabled: Boolean,
    onVideoTorchToggle: () -> Unit,
    videoHdrEnabled: Boolean,
    onVideoHdrToggle: () -> Unit,
    videoStabilizationMode: VideoStabilizationMode,
    videoActionModeAvailable: Boolean,
    onVideoStabilizationModeChange: (VideoStabilizationMode) -> Unit,
    onVideoEditClick: () -> Unit,
    onVideoDualCaptureClick: () -> Unit,
    videoCodec: VideoCodec,
    onVideoCodecChange: (VideoCodec) -> Unit,
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
    contentTopPadding: Dp = CameraTopSheetContentTopPadding,
    modifier: Modifier = Modifier,
    onGlassBoundsChanged: (Rect?) -> Unit = {},
) {
    var expandedVideoPanel by rememberSaveable { mutableStateOf<VideoSettingPanel?>(null) }
    var showPhotoAspectOptions by rememberSaveable { mutableStateOf(false) }
    var showRenderingEngineSheet by rememberSaveable { mutableStateOf(false) }
    var showOppoPipelineSheet by rememberSaveable { mutableStateOf(false) }
    var showContentManagementOptions by rememberSaveable { mutableStateOf(false) }
    fun handleContentManagementAction(action: () -> Unit) {
        showContentManagementOptions = false
        action()
    }

    LaunchedEffect(visible, captureMode) {
        showContentManagementOptions = false
        showPhotoAspectOptions = false
    }
    LaunchedEffect(visible) {
        if (!visible) onGlassBoundsChanged(null)
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
        // The menu scales from the six-dot trigger's top-right position. As the
        // top bar disappears, the card grows out from that same point instead of
        // appearing as an unrelated sheet over the controls.
        enter = fadeIn(animationSpec = tween(110)) +
            scaleIn(
                initialScale = 0.035f,
                transformOrigin = TransformOrigin(0.94f, 0.04f),
                animationSpec = spring(dampingRatio = 0.86f, stiffness = 300f)
            ) +
            expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = spring(dampingRatio = 0.88f, stiffness = 320f)
            ),
        exit = fadeOut(animationSpec = tween(120)) +
            scaleOut(
                targetScale = 0.94f,
                transformOrigin = TransformOrigin(0.94f, 0.04f),
                animationSpec = tween(140)
            ) +
            shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(150)
            ),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = contentTopPadding, start = 5.dp, end = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .onGloballyPositioned { coordinates ->
                    onGlassBoundsChanged(coordinates.boundsInRoot())
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
                    .autoRotate()
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                if (captureMode == CaptureMode.PHOTO) {
                    val isProfessionalMode = useRaw && isRawSupported
                    Column(modifier = Modifier.padding(vertical = 16.dp)) {
                        QuickSettingGrid(columns = 3, itemHeight = 90.dp) { itemModifier ->
                            VideoMenuItem(
                                label = "REFOCUS",
                                active = oppoPhotoSettings.refocusEnabled,
                                enabled = refocusAvailable && !isProfessionalMode,
                                modifier = itemModifier,
                                onClick = {
                                    val enabled = !oppoPhotoSettings.refocusEnabled
                                    onOppoPhotoSettingsChange(
                                        oppoPhotoSettings.copy(
                                            refocusEnabled = enabled,
                                            autoHdrEnabled = if (enabled) false else oppoPhotoSettings.autoHdrEnabled
                                        )
                                    )
                                }
                            ) { tint ->
                                PhotoRefocusGlyph(tint)
                            }
                            VideoMenuItem(
                                label = "LIVE",
                                active = useLivePhoto,
                                modifier = itemModifier,
                                onClick = onLivePhotoToggle
                            ) { tint ->
                                PhotoLiveGlyph(tint)
                            }
                            VideoMenuItem(
                                label = "ASPECT",
                                modifier = itemModifier,
                                onClick = { showPhotoAspectOptions = !showPhotoAspectOptions }
                            ) { tint ->
                                PhotoAspectGlyph(tint, aspectRatio.getDisplayName())
                            }
                            VideoMenuItem(
                                label = "TIMER",
                                active = timerSeconds > 0,
                                modifier = itemModifier,
                                onClick = onTimerToggle
                            ) { tint ->
                                PhotoTimerGlyph(tint, timerSeconds)
                            }
                            VideoMenuItem(
                                label = "BRUST",
                                active = oppoPhotoSettings.fastShutterEnabled,
                                enabled = !isProfessionalMode,
                                modifier = itemModifier,
                                onClick = {
                                    onOppoPhotoSettingsChange(
                                        oppoPhotoSettings.copy(
                                            fastShutterEnabled = !oppoPhotoSettings.fastShutterEnabled
                                        )
                                    )
                                }
                            ) { tint ->
                                PhotoBurstGlyph(tint)
                            }
                            VideoMenuItem(
                                label = "STYLES",
                                active = if (isProfessionalMode) true else oppoPhotoSettings.customPipelineEnabled,
                                modifier = itemModifier,
                                onClick = {
                                    if (isProfessionalMode) {
                                        showRenderingEngineSheet = true
                                    } else {
                                        showOppoPipelineSheet = true
                                    }
                                }
                            ) { tint ->
                                PhotoStylesGlyph(tint)
                            }
                            VideoMenuItem(
                                label = "FILTER",
                                modifier = itemModifier,
                                onClick = onPhotoFilterClick
                            ) { tint ->
                                PhotoFilterGlyph(tint)
                            }
                            VideoMenuItem(
                                label = "DEPTH",
                                modifier = itemModifier,
                                onClick = onPhotoDepthClick
                            ) { tint ->
                                PhotoDepthGlyph(tint)
                            }
                            VideoMenuItem(
                                label = "SETTINGS",
                                modifier = itemModifier,
                                onClick = onMoreSettingsClick
                            ) { tint ->
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = showPhotoAspectOptions,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                Spacer(modifier = Modifier.height(12.dp))
                                VideoSettingExpandedPanel {
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        AspectRatio.sanitizeTopSheetRatios(topSheetAspectRatios).forEach { ratio ->
                                            VideoOptionChip(
                                                title = ratio.getDisplayName(),
                                                selected = aspectRatio == ratio,
                                                onClick = {
                                                    showPhotoAspectOptions = false
                                                    onAspectRatioChange(ratio)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // The floating window's outer bounds stay unchanged; only the video
                    // menu content is replaced by the reference's 3x3 circular icon grid.
                    Column(modifier = Modifier.padding(vertical = 16.dp)) {
                        QuickSettingGrid(columns = 3, itemHeight = 90.dp) { itemModifier ->
                            VideoMenuItem(
                                label = "FLASH",
                                active = videoTorchEnabled,
                                modifier = itemModifier,
                                onClick = onVideoTorchToggle
                            ) { tint ->
                                Icon(
                                    imageVector = if (videoTorchEnabled) AppIcons.FlashOn else AppIcons.FlashOff,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            VideoMenuItem(
                                label = "EDIT",
                                modifier = itemModifier,
                                onClick = onVideoEditClick
                            ) { tint ->
                                VideoEditGlyph(tint)
                            }
                            VideoMenuItem(
                                label = "ACTION",
                                active = videoStabilizationMode == VideoStabilizationMode.ENHANCED,
                                enabled = videoActionModeAvailable,
                                modifier = itemModifier,
                                onClick = {
                                    if (videoActionModeAvailable) {
                                        onVideoStabilizationModeChange(
                                            if (videoStabilizationMode == VideoStabilizationMode.ENHANCED) {
                                                VideoStabilizationMode.OFF
                                            } else {
                                                VideoStabilizationMode.ENHANCED
                                            }
                                        )
                                    }
                                }
                            ) { tint ->
                                VideoActionGlyph(
                                    tint = tint,
                                    active = videoStabilizationMode == VideoStabilizationMode.ENHANCED
                                )
                            }
                            VideoMenuItem(
                                label = "DUAL CAPTURE",
                                modifier = itemModifier,
                                onClick = onVideoDualCaptureClick
                            ) { tint ->
                                VideoDualCaptureGlyph(tint)
                            }
                            VideoMenuItem(
                                label = "FORMAT",
                                active = videoHdrEnabled,
                                modifier = itemModifier,
                                onClick = {
                                    expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.CODEC) {
                                        null
                                    } else {
                                        VideoSettingPanel.CODEC
                                    }
                                }
                            ) { tint ->
                                Text(
                                    text = if (videoHdrEnabled) "HDR" else "SDR",
                                    color = tint,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            VideoMenuItem(
                                label = "BITRATE",
                                modifier = itemModifier,
                                onClick = {
                                    expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.BITRATE) {
                                        null
                                    } else {
                                        VideoSettingPanel.BITRATE
                                    }
                                }
                            ) { tint ->
                                Text(
                                    text = videoBitrate.bitrateMbps.toString(),
                                    color = tint,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            VideoMenuItem(
                                label = "LOG",
                                active = videoLogProfile.isEnabled,
                                modifier = itemModifier,
                                onClick = {
                                    expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.LOG_PROFILE) {
                                        null
                                    } else {
                                        VideoSettingPanel.LOG_PROFILE
                                    }
                                }
                            ) { tint ->
                                VideoLogGlyph(tint)
                            }
                            VideoMenuItem(
                                label = "ASPECTS\nRATIO",
                                modifier = itemModifier,
                                onClick = {
                                    expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.ASPECT_RATIO) {
                                        null
                                    } else {
                                        VideoSettingPanel.ASPECT_RATIO
                                    }
                                }
                            ) { tint ->
                                VideoAspectRatioGlyph(tint)
                            }
                            VideoMenuItem(
                                label = "SETTINGS",
                                modifier = itemModifier,
                                onClick = onMoreSettingsClick
                            ) { tint ->
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }

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

                                        VideoSettingPanel.CODEC -> {
                                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Column {
                                                    SectionLabel(title = "DYNAMIC RANGE")
                                                    FlowRow(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        VideoOptionChip(
                                                            title = "HDR",
                                                            selected = videoHdrEnabled,
                                                            enabled = !videoLogProfile.isEnabled,
                                                            onClick = {
                                                                if (!videoHdrEnabled && !videoLogProfile.isEnabled) onVideoHdrToggle()
                                                            }
                                                        )
                                                        VideoOptionChip(
                                                            title = "SDR",
                                                            selected = !videoHdrEnabled,
                                                            enabled = !videoLogProfile.isEnabled,
                                                            onClick = {
                                                                if (videoHdrEnabled && !videoLogProfile.isEnabled) onVideoHdrToggle()
                                                            }
                                                        )
                                                    }
                                                }
                                                Column {
                                                    SectionLabel(title = "VIDEO CODEC")
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
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun PhotoRefocusGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(38.dp)) {
        val stroke = 2.2.dp.toPx()
        val left = size.width * 0.14f
        val right = size.width * 0.86f
        val top = size.height * 0.14f
        val bottom = size.height * 0.86f
        val leg = size.width * 0.22f
        drawLine(tint, Offset(left, top + leg), Offset(left, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, top), Offset(left + leg, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right - leg, top), Offset(right, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right, top), Offset(right, top + leg), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, bottom - leg), Offset(left, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, bottom), Offset(left + leg, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right - leg, bottom), Offset(right, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right, bottom), Offset(right, bottom - leg), stroke, cap = StrokeCap.Round)
        drawCircle(tint, radius = 3.5.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f))
    }
}

@Composable
private fun PhotoLiveGlyph(tint: Color) {
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width * 0.46f, size.height * 0.47f)
            val base = size.minDimension * 0.35f
            val stroke = 1.8.dp.toPx()
            drawCircle(tint, radius = base, center = center, style = Stroke(width = stroke))
            drawCircle(tint, radius = base * 0.72f, center = center, style = Stroke(width = stroke))
            drawCircle(tint, radius = base * 0.40f, center = center, style = Stroke(width = stroke))
            drawCircle(tint, radius = base * 0.12f, center = center)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(17.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = Color(0xFF17171B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PhotoAspectGlyph(tint: Color, ratioLabel: String) {
    Box(modifier = Modifier.size(38.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = 2.1.dp.toPx()
            val left = size.width * 0.08f
            val right = size.width * 0.92f
            val top = size.height * 0.12f
            val bottom = size.height * 0.88f
            val leg = size.width * 0.20f
            drawLine(tint, Offset(left, top + leg), Offset(left, top), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(left, top), Offset(left + leg, top), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(right - leg, top), Offset(right, top), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(right, top), Offset(right, top + leg), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(left, bottom - leg), Offset(left, bottom), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(left, bottom), Offset(left + leg, bottom), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(right - leg, bottom), Offset(right, bottom), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(right, bottom), Offset(right, bottom - leg), stroke, cap = StrokeCap.Round)
        }
        Text(
            text = ratioLabel,
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PhotoTimerGlyph(tint: Color, timerSeconds: Int) {
    Canvas(modifier = Modifier.size(38.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.34f
        val stroke = 2.2.dp.toPx()
        drawCircle(tint, radius = radius, center = center, style = Stroke(width = stroke))
        drawLine(tint, Offset(center.x, center.y - radius * 0.60f), center, stroke, cap = StrokeCap.Round)
        drawLine(tint, center, Offset(center.x + radius * 0.48f, center.y + radius * 0.30f), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(center.x, center.y - radius - 4.dp.toPx()), Offset(center.x, center.y - radius - 1.dp.toPx()), stroke, cap = StrokeCap.Round)
        if (timerSeconds == 0) {
            drawLine(
                tint,
                Offset(size.width * 0.16f, size.height * 0.16f),
                Offset(size.width * 0.84f, size.height * 0.84f),
                stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun PhotoBurstGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(40.dp)) {
        val stroke = 1.9.dp.toPx()
        val radius = CornerRadius(3.dp.toPx())
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.28f, size.height * 0.08f),
            size = Size(size.width * 0.62f, size.height * 0.54f),
            cornerRadius = radius,
            style = Stroke(width = stroke)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.16f, size.height * 0.22f),
            size = Size(size.width * 0.62f, size.height * 0.54f),
            cornerRadius = radius,
            style = Stroke(width = stroke)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.05f, size.height * 0.36f),
            size = Size(size.width * 0.62f, size.height * 0.54f),
            cornerRadius = radius,
            style = Stroke(width = stroke)
        )
    }
}

@Composable
private fun PhotoStylesGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(40.dp)) {
        val stroke = 2.0.dp.toPx()
        fun drawLayer(left: Float, top: Float, right: Float, bottom: Float) {
            val path = Path().apply {
                moveTo(left, top + size.height * 0.10f)
                lineTo(right - size.width * 0.08f, top)
                lineTo(right, bottom - size.height * 0.10f)
                lineTo(left + size.width * 0.08f, bottom)
                close()
            }
            drawPath(path, tint, style = Stroke(width = stroke))
        }
        drawLayer(size.width * 0.25f, size.height * 0.04f, size.width * 0.84f, size.height * 0.77f)
        drawLayer(size.width * 0.12f, size.height * 0.18f, size.width * 0.71f, size.height * 0.90f)
    }
}

@Composable
private fun PhotoFilterGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(40.dp)) {
        val stroke = 2.1.dp.toPx()
        drawCircle(tint, radius = size.minDimension * 0.23f, center = Offset(size.width * 0.42f, size.height * 0.38f), style = Stroke(width = stroke))
        drawCircle(tint, radius = size.minDimension * 0.23f, center = Offset(size.width * 0.60f, size.height * 0.58f), style = Stroke(width = stroke))
        drawCircle(tint, radius = size.minDimension * 0.23f, center = Offset(size.width * 0.35f, size.height * 0.65f), style = Stroke(width = stroke))
    }
}

@Composable
private fun PhotoDepthGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(40.dp)) {
        val stroke = 2.2.dp.toPx()
        val left = size.width * 0.19f
        val right = size.width * 0.80f
        val top = size.height * 0.16f
        val bottom = size.height * 0.84f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(size.width * 0.54f, size.height * 0.68f),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(width = stroke)
        )
        drawRoundRect(
            color = tint.copy(alpha = 0.72f),
            topLeft = Offset(size.width * 0.34f, size.height * 0.27f),
            size = Size(size.width * 0.48f, size.height * 0.57f),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(width = stroke)
        )
        drawLine(
            tint,
            Offset(size.width * 0.13f, bottom),
            Offset(size.width * 0.83f, top),
            stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun VideoMenuItem(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit
) {
    // The parent menu is the only glass surface. Keep shortcuts as transparent
    // icon/label content so their own disks, shadows and borders cannot stack over it.
    val iconTint = when {
        !enabled -> Color.White.copy(alpha = 0.36f)
        active -> Color.White
        else -> Color.White.copy(alpha = 0.78f)
    }
    Column(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(62.dp),
            contentAlignment = Alignment.Center
        ) {
            icon(iconTint)
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.42f),
            fontSize = 11.sp,
            lineHeight = 12.sp,
            letterSpacing = 0.30.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun VideoEditGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(36.dp)) {
        val stroke = 2.3.dp.toPx()
        val centerX = size.width / 2f
        drawLine(tint, Offset(centerX, size.height * 0.06f), Offset(centerX, size.height * 0.47f), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.18f, size.height * 0.27f), Offset(size.width * 0.82f, size.height * 0.27f), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.18f, size.height * 0.73f), Offset(size.width * 0.82f, size.height * 0.73f), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun VideoActionGlyph(tint: Color, active: Boolean) {
    Canvas(modifier = Modifier.size(38.dp)) {
        val scale = size.minDimension / 32f
        val stroke = 2.0.dp.toPx()
        fun p(x: Float, y: Float) = Offset(x * scale, y * scale)
        drawLine(tint, p(2f, 10f), p(9f, 10f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(1f, 17f), p(7f, 17f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(2f, 24f), p(6f, 24f), stroke, cap = StrokeCap.Round)
        drawCircle(tint, radius = 2.5f * scale, center = p(21f, 6.5f))
        drawLine(tint, p(18.5f, 10f), p(15f, 17f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(16.5f, 12f), p(11f, 10f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(11f, 10f), p(8f, 13f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(17f, 13f), p(24f, 15f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(24f, 15f), p(28f, 12f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(15f, 17f), p(11f, 24f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(11f, 24f), p(6f, 28f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(15f, 17f), p(21f, 23f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(21f, 23f), p(27f, 27f), stroke, cap = StrokeCap.Round)
        if (!active) drawLine(tint, p(5f, 2.5f), p(28f, 29.5f), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun VideoDualCaptureGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(38.dp)) {
        val scale = size.minDimension / 32f
        val stroke = 1.9.dp.toPx()
        drawRoundRect(color = tint, topLeft = Offset(3f * scale, 4f * scale), size = Size(19f * scale, 15f * scale), cornerRadius = CornerRadius(2.2f * scale), style = Stroke(width = stroke))
        drawRoundRect(color = tint, topLeft = Offset(10f * scale, 12f * scale), size = Size(19f * scale, 15f * scale), cornerRadius = CornerRadius(2.2f * scale), style = Stroke(width = stroke))
        drawCircle(tint, radius = 2.2f * scale, center = Offset(20f * scale, 18f * scale))
        drawLine(tint, Offset(16.5f * scale, 24f * scale), Offset(23.5f * scale, 24f * scale), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun VideoLogGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(38.dp)) {
        val stroke = 2.6.dp.toPx()
        val left = size.width * 0.16f
        val right = size.width * 0.84f
        val top = size.height * 0.16f
        val bottom = size.height * 0.84f
        val leg = size.width * 0.19f
        drawLine(tint, Offset(left, top + leg), Offset(left, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, top), Offset(left + leg, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right - leg, top), Offset(right, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right, top), Offset(right, top + leg), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, bottom - leg), Offset(left, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, bottom), Offset(left + leg, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right - leg, bottom), Offset(right, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right, bottom), Offset(right, bottom - leg), stroke, cap = StrokeCap.Round)
        drawCircle(tint, radius = 2.6.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f))
    }
}

@Composable
private fun VideoAspectRatioGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(38.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.34f
        val dotRadius = 1.15.dp.toPx()
        repeat(12) { index ->
            val angle = Math.toRadians((index * 30.0 - 90.0))
            val x = center.x + kotlin.math.cos(angle).toFloat() * radius
            val y = center.y + kotlin.math.sin(angle).toFloat() * radius
            drawCircle(tint, radius = dotRadius, center = Offset(x, y))
        }
        drawLine(tint, center, Offset(center.x, center.y - radius * 0.74f), 2.2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(tint, radius = 2.2.dp.toPx(), center = center)
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
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = title,
            color = if (expanded) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.72f),
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
                color = if (expanded) Color.White else Color.White,
                maxFontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.OpenInFull,
                contentDescription = null,
                tint = if (expanded) Color.White else Color.White.copy(alpha = 0.8f),
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
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun VideoOptionChip(
    title: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = when {
                !enabled -> Color.White.copy(alpha = 0.38f)
                selected -> Color.White
                else -> Color.White.copy(alpha = 0.72f)
            },
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
                if (checked) Color.White else Color.White.copy(
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
                color = if (checked) Color.White else Color.White.copy(alpha = 0.9f),
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = if (checked) Color.White else Color.White.copy(alpha = 0.9f),
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
                if (checked) Color.White.copy(alpha = contentAlpha) else Color.White.copy(
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
                    Color.White.copy(alpha = contentAlpha)
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
                            Color.White.copy(alpha = contentAlpha)
                        } else {
                            Color.White.copy(alpha = 0.2f * contentAlpha)
                        }
                    )
            )
        }
    }
}
