package com.hinnka.mycamera.ui.camera

import android.media.AudioDeviceInfo
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
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
import com.hinnka.mycamera.ui.components.glassUiSurface
import com.hinnka.mycamera.video.*
import com.hinnka.mycamera.video.VideoCodec
import com.hinnka.mycamera.ui.icons.AppIcons

private enum class VideoSettingPanel {
    ASPECT_RATIO,
    LOG_PROFILE,
    BITRATE
}

private enum class VideoMenuGlyph {
    EDIT,
    ACTION,
    DUAL_CAPTURE,
    LOG,
    ASPECT_RATIO
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
    videoTorchEnabled: Boolean,
    videoTorchAvailable: Boolean,
    onVideoTorchToggle: () -> Unit,
    videoHdrEnabled: Boolean,
    onVideoHdrToggle: () -> Unit,
    videoStabilizationMode: VideoStabilizationMode,
    videoActionAvailable: Boolean,
    onVideoActionToggle: () -> Unit,
    onVideoEditClick: () -> Unit,
    onVideoDualCaptureClick: () -> Unit,
    videoLogProfile: VideoLogProfile,
    availableVideoLogProfiles: List<VideoLogProfile>,
    onVideoLogProfileChange: (VideoLogProfile) -> Unit,
    videoBitrate: VideoBitratePreset,
    onVideoBitrateChange: (VideoBitratePreset) -> Unit,
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
    modifier: Modifier = Modifier
) {
    var expandedVideoPanel by rememberSaveable { mutableStateOf<VideoSettingPanel?>(null) }
    var showRenderingEngineSheet by rememberSaveable { mutableStateOf(false) }
    var showOppoPipelineSheet by rememberSaveable { mutableStateOf(false) }
    var showContentManagementOptions by rememberSaveable { mutableStateOf(false) }
    fun handleContentManagementAction(action: () -> Unit) {
        showContentManagementOptions = false
        action()
    }

    LaunchedEffect(visible, captureMode) {
        showContentManagementOptions = false
        expandedVideoPanel = null
    }

    LaunchedEffect(useRaw) {
        if (!useRaw) {
            showRenderingEngineSheet = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .glassUiSurface(
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                    tint = Color(0xC9191B22),
                    rimStrength = 0.9f
                )
                // Keep the screen's top inset fixed, but measure content and side padding
                // in the rotated viewport so landscape controls stay inside the sheet.
                .padding(top = contentTopPadding)
                .autoRotate(matchParentSize = true)
                .padding(horizontal = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (captureMode == CaptureMode.PHOTO || expandedVideoPanel != null) {
                            Modifier.verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        }
                    )
            ) {
                if (captureMode == CaptureMode.PHOTO) {
                    val isProfessionalMode = useRaw && isRawSupported
                    SectionLabel(title = stringResource(R.string.aspect_ratio))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AspectRatio.sanitizeTopSheetRatios(topSheetAspectRatios).forEach { ratio ->
                            val isSelected = aspectRatio == ratio
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) AccentColor else Color.White.copy(
                                            alpha = 0.12f
                                        )
                                    )
                                    .clickable { onAspectRatioChange(ratio) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = ratio.getDisplayName(),
                                    color = if (isSelected) OnAccentColor else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    QuickSettingGrid { itemModifier ->
                        if (isProfessionalMode) {
                            QuickSettingButton2(
                                title = stringResource(R.string.settings_raw_color_engine),
                                checked = true,
                                onClick = { showRenderingEngineSheet = true },
                                modifier = itemModifier
                            )
                        } else {
                            QuickSettingToggle(
                                title = stringResource(R.string.oppo_auto_hdr),
                                checked = oppoPhotoSettings.autoHdrEnabled,
                                onCheckedChange = { enabled ->
                                    onOppoPhotoSettingsChange(
                                        oppoPhotoSettings.copy(
                                            autoHdrEnabled = enabled,
                                            refocusEnabled = if (enabled) false else oppoPhotoSettings.refocusEnabled,
                                        )
                                    )
                                },
                                modifier = itemModifier
                            )
                            QuickSettingToggle(
                                title = stringResource(R.string.oppo_fast_shutter),
                                checked = oppoPhotoSettings.fastShutterEnabled,
                                onCheckedChange = {
                                    onOppoPhotoSettingsChange(oppoPhotoSettings.copy(fastShutterEnabled = it))
                                },
                                modifier = itemModifier
                            )
                            QuickSettingToggle(
                                title = stringResource(R.string.oppo_refocus),
                                checked = oppoPhotoSettings.refocusEnabled,
                                onCheckedChange = { enabled ->
                                    onOppoPhotoSettingsChange(
                                        oppoPhotoSettings.copy(
                                            refocusEnabled = enabled,
                                            autoHdrEnabled = if (enabled) false else oppoPhotoSettings.autoHdrEnabled,
                                        )
                                    )
                                },
                                enabled = refocusAvailable,
                                modifier = itemModifier
                            )
                            QuickSettingButton2(
                                title = stringResource(R.string.oppo_custom_pipeline),
                                checked = oppoPhotoSettings.customPipelineEnabled,
                                onClick = { showOppoPipelineSheet = true },
                                modifier = itemModifier
                            )
                        }
                        MeteringModeQuickSetting(
                            meteringMode = meteringMode,
                            onMeteringModeChange = onMeteringModeChange,
                            modifier = itemModifier
                        )
                        ToolboxQuickSetting(
                            onToolboxClick = onToolboxClick,
                            modifier = itemModifier
                        )
                        ContentManagementQuickSetting(
                            onClick = { showContentManagementOptions = !showContentManagementOptions },
                            modifier = itemModifier
                        )
                    }

                    ContentManagementOptionsPanel(
                        visible = showContentManagementOptions,
                        onFilterManageClick = {
                            handleContentManagementAction(onFilterManageClick)
                        },
                        onFrameManageClick = {
                            handleContentManagementAction(onFrameManageClick)
                        },
                        onPresetManageClick = {
                            handleContentManagementAction(onPresetManageClick)
                        }
                    )
                } else {
                    // Keep the existing sheet frame unchanged and replace only its video-mode contents.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (expandedVideoPanel == null) {
                                    Modifier.weight(1f)
                                } else {
                                    Modifier.height(292.dp)
                                }
                            ),
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            VideoMenuItem(
                                label = "FLASH",
                                icon = if (videoTorchEnabled) AppIcons.FlashOn else AppIcons.FlashOff,
                                selected = videoTorchEnabled,
                                enabled = videoTorchAvailable,
                                onClick = onVideoTorchToggle,
                                modifier = Modifier.weight(1f)
                            )
                            VideoMenuItem(
                                label = "EDIT",
                                glyph = VideoMenuGlyph.EDIT,
                                onClick = onVideoEditClick,
                                modifier = Modifier.weight(1f)
                            )
                            VideoMenuItem(
                                label = "ACTION",
                                glyph = VideoMenuGlyph.ACTION,
                                selected = videoStabilizationMode == VideoStabilizationMode.ENHANCED,
                                enabled = videoActionAvailable,
                                onClick = onVideoActionToggle,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            VideoMenuItem(
                                label = "DUAL CAPTURE",
                                glyph = VideoMenuGlyph.DUAL_CAPTURE,
                                onClick = onVideoDualCaptureClick,
                                modifier = Modifier.weight(1f)
                            )
                            VideoMenuItem(
                                label = "FORMAT",
                                centerText = "HDR",
                                selected = videoHdrEnabled,
                                onClick = onVideoHdrToggle,
                                modifier = Modifier.weight(1f)
                            )
                            VideoMenuItem(
                                label = "BITRATE",
                                centerText = videoBitrate.bitrateMbps.toString(),
                                selected = expandedVideoPanel == VideoSettingPanel.BITRATE,
                                onClick = {
                                    expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.BITRATE) {
                                        null
                                    } else {
                                        VideoSettingPanel.BITRATE
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            VideoMenuItem(
                                label = "LOG",
                                glyph = VideoMenuGlyph.LOG,
                                selected = videoLogProfile != VideoLogProfile.OFF ||
                                    expandedVideoPanel == VideoSettingPanel.LOG_PROFILE,
                                onClick = {
                                    expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.LOG_PROFILE) {
                                        null
                                    } else {
                                        VideoSettingPanel.LOG_PROFILE
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            VideoMenuItem(
                                label = "ASPECTS RATIO",
                                glyph = VideoMenuGlyph.ASPECT_RATIO,
                                selected = expandedVideoPanel == VideoSettingPanel.ASPECT_RATIO,
                                onClick = {
                                    expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.ASPECT_RATIO) {
                                        null
                                    } else {
                                        VideoSettingPanel.ASPECT_RATIO
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            VideoMenuItem(
                                label = "SETTINGS",
                                icon = Icons.Default.Settings,
                                onClick = onMoreSettingsClick,
                                modifier = Modifier.weight(1f)
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
                                                    onClick = {
                                                        onVideoAspectRatioChange(ratio)
                                                        expandedVideoPanel = null
                                                    }
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
                                                    onClick = {
                                                        onVideoLogProfileChange(profile)
                                                        expandedVideoPanel = null
                                                    }
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
                                                    onClick = {
                                                        onVideoBitrateChange(bitrate)
                                                        expandedVideoPanel = null
                                                    }
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

                if (captureMode == CaptureMode.PHOTO) {
                    // More Settings Button
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable(onClick = onMoreSettingsClick)
                                .glassUiSurface(
                                    shape = RoundedCornerShape(8.dp),
                                    tint = Color(0x251FFFFFF),
                                    rimStrength = 0.75f
                                )
                                .padding(16.dp, 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .glassUiSurface(
                                        shape = RoundedCornerShape(8.dp),
                                        tint = Color(0x20FFFFFF),
                                        rimStrength = 0.65f
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.settings_title),
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = AppIcons.ChevronRight,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(20.dp)
                            )
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
                color = AccentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun VideoMenuItem(
    label: String,
    icon: ImageVector? = null,
    glyph: VideoMenuGlyph? = null,
    centerText: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .glassUiSurface(
                    shape = CircleShape,
                    tint = Color(0xA90B0D12),
                    rimStrength = if (selected) 1.35f else 0.8f
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (enabled) 1f else 0.38f),
                    modifier = Modifier.size(34.dp)
                )
                glyph != null -> VideoMenuGlyphIcon(
                    glyph = glyph,
                    tint = Color.White.copy(alpha = if (enabled) 1f else 0.38f),
                    modifier = Modifier.size(34.dp)
                )
                centerText != null -> Text(
                    text = centerText,
                    color = Color.White.copy(alpha = if (enabled) 1f else 0.38f),
                    fontSize = if (centerText.length >= 4) 21.sp else 23.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = if (enabled) 0.96f else 0.38f),
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.25.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun VideoMenuGlyphIcon(
    glyph: VideoMenuGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val scale = size.minDimension / 34f
        val stroke = 2.2f * scale

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(
                color = tint,
                start = Offset(x1 * scale, y1 * scale),
                end = Offset(x2 * scale, y2 * scale),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }

        when (glyph) {
            VideoMenuGlyph.EDIT -> {
                line(17f, 4f, 17f, 18f)
                line(9f, 11f, 25f, 11f)
                line(9f, 25f, 25f, 25f)
            }
            VideoMenuGlyph.ACTION -> {
                // Motion trails, head, torso and running limbs.
                line(1f, 10f, 9f, 10f)
                line(3f, 17f, 8f, 17f)
                line(1f, 24f, 7f, 24f)
                drawCircle(tint, radius = 3.2f * scale, center = Offset(23f * scale, 5.5f * scale))
                line(19f, 10f, 15f, 18f)
                line(15f, 18f, 23f, 21f)
                line(18f, 12f, 25f, 14f)
                line(18f, 12f, 25f, 9f)
                line(15f, 18f, 9f, 27f)
                line(15f, 18f, 22f, 29f)
                line(23f, 21f, 29f, 27f)
            }
            VideoMenuGlyph.DUAL_CAPTURE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(3f * scale, 5f * scale),
                    size = Size(23f * scale, 17f * scale),
                    cornerRadius = CornerRadius(2.5f * scale),
                    style = Stroke(strokeWidth = stroke)
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(10f * scale, 12f * scale),
                    size = Size(21f * scale, 17f * scale),
                    cornerRadius = CornerRadius(2.5f * scale),
                    style = Stroke(strokeWidth = stroke)
                )
                drawCircle(tint, radius = 2.4f * scale, center = Offset(23.5f * scale, 20f * scale))
                line(19f, 27f, 28f, 27f)
            }
            VideoMenuGlyph.LOG -> {
                line(4f, 12f, 4f, 5f)
                line(4f, 5f, 11f, 5f)
                line(23f, 5f, 30f, 5f)
                line(30f, 5f, 30f, 12f)
                line(4f, 22f, 4f, 29f)
                line(4f, 29f, 11f, 29f)
                line(23f, 29f, 30f, 29f)
                line(30f, 29f, 30f, 22f)
                drawCircle(tint, radius = 3f * scale, center = Offset(17f * scale, 17f * scale))
            }
            VideoMenuGlyph.ASPECT_RATIO -> {
                drawCircle(
                    color = tint.copy(alpha = 0.9f),
                    radius = 12.5f * scale,
                    center = Offset(17f * scale, 18f * scale),
                    style = Stroke(width = 1.8f * scale)
                )
                for (index in 0 until 12) {
                    val angle = index * (Math.PI / 6.0) - Math.PI / 2.0
                    val x = 17f + kotlin.math.cos(angle).toFloat() * 14.5f
                    val y = 18f + kotlin.math.sin(angle).toFloat() * 14.5f
                    drawCircle(tint, radius = 0.9f * scale, center = Offset(x * scale, y * scale))
                }
                line(17f, 18f, 14f, 7f)
                line(17f, 18f, 19f, 18f)
            }
        }
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
            .glassUiSurface(
                shape = RoundedCornerShape(8.dp),
                tint = if (expanded) AccentColor else Color(0x251FFFFFF),
                rimStrength = if (expanded) 1.15f else 0.75f
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
            .glassUiSurface(
                shape = RoundedCornerShape(14.dp),
                tint = Color(0xB51A1C22),
                rimStrength = 0.8f
            )
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
            .glassUiSurface(
                shape = RoundedCornerShape(8.dp),
                tint = if (selected) AccentColor else Color(0x251FFFFFF),
                rimStrength = if (selected) 1.15f else 0.75f
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
            .glassUiSurface(
                shape = RoundedCornerShape(8.dp),
                tint = Color(0x251FFFFFF),
                rimStrength = 0.75f
            )
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
            .glassUiSurface(
                shape = RoundedCornerShape(8.dp),
                tint = Color(0x251FFFFFF),
                rimStrength = 0.75f
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
            .glassUiSurface(
                shape = RoundedCornerShape(8.dp),
                tint = if (checked) AccentColor else Color(0x251FFFFFF),
                rimStrength = if (checked) 1.15f else 0.75f
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
            .glassUiSurface(
                shape = RoundedCornerShape(8.dp),
                tint = if (checked) AccentColor.copy(alpha = contentAlpha) else Color(0x251FFFFFF),
                rimStrength = if (checked) 1.15f else 0.75f
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
