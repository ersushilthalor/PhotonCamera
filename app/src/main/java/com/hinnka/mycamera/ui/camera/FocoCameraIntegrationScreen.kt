package com.hinnka.mycamera.ui.camera

import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hinnka.mycamera.camera.AspectRatio
import com.hinnka.mycamera.camera.GridStyle
import com.hinnka.mycamera.data.VolumeKeyAction as PhotonVolumeKeyAction
import com.hinnka.mycamera.focoui.camera.LensKind
import com.hinnka.mycamera.focoui.device.MicStatus
import com.hinnka.mycamera.focoui.settings.AppSettings as FocoSettings
import com.hinnka.mycamera.focoui.settings.FlashSetting
import com.hinnka.mycamera.focoui.settings.FrameRate
import com.hinnka.mycamera.focoui.settings.GridSetting
import com.hinnka.mycamera.focoui.settings.RatioSetting
import com.hinnka.mycamera.focoui.settings.ResolutionSetting
import com.hinnka.mycamera.focoui.settings.SettingKey
import com.hinnka.mycamera.focoui.settings.TimerSetting
import com.hinnka.mycamera.focoui.settings.VideoResolution
import com.hinnka.mycamera.focoui.ui.AppState as FocoAppState
import com.hinnka.mycamera.focoui.ui.CameraActions as FocoActions
import com.hinnka.mycamera.focoui.ui.CaptureKind
import com.hinnka.mycamera.focoui.ui.PhotoMode
import com.hinnka.mycamera.focoui.ui.ProState
import com.hinnka.mycamera.focoui.ui.Readout
import com.hinnka.mycamera.focoui.ui.SettingsScreen as FocoSettingsScreen
import com.hinnka.mycamera.focoui.ui.theme.FocoTheme
import com.hinnka.mycamera.video.CaptureMode
import com.hinnka.mycamera.video.VideoFpsPreset
import com.hinnka.mycamera.video.VideoResolutionPreset
import com.hinnka.mycamera.video.VideoStabilizationMode
import com.hinnka.mycamera.viewmodel.CameraViewModel
import com.hinnka.mycamera.viewmodel.GalleryViewModel

@Composable
fun FocoCameraIntegrationScreen(
    viewModel: CameraViewModel,
    galleryViewModel: GalleryViewModel,
    onGalleryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onFilterManagementClick: (String?) -> Unit,
    onFrameManagementClick: () -> Unit,
    onToolboxClick: () -> Unit,
    onPresetEditClick: (String?) -> Unit,
    onPresetManagementClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val localView = LocalView.current
    val photon by viewModel.state.collectAsState()
    val initialized by viewModel.isInitialized.collectAsState()
    val baselineRecipe by viewModel.currentBaselineRecipeParams.collectAsState()
    val photoRecipe by viewModel.currentRecipeParams.collectAsState()
    val videoRecipe by viewModel.currentVideoRecipeParams.collectAsState()
    val levelIndicator by viewModel.showLevelIndicator.collectAsState(initial = false)
    val videoHdrEnabled by viewModel.videoHdrEnabled.collectAsState()
    val focoState = remember { FocoAppState().apply { permissionGranted = true } }
    val focoSettings = remember(context) { FocoSettings(context) }
    var previewSurfaceTexture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var cameraPrepared by remember { mutableStateOf(false) }
    val currentCamera = photon.getCurrentCameraInfo()
    val minZoom = viewModel.globalMinZoom.coerceAtLeast(0.1f)
    val maxZoom = viewModel.globalMaxZoom.coerceAtLeast(minZoom)
    val zoomStops = remember(photon.availableCameras, minZoom, maxZoom) {
        (photon.availableCameras.flatMap { it.zoomSteps } + listOf(minZoom, 1f, 2f, 5f))
            .filter { it >= minZoom && it <= maxZoom }
            .distinct()
            .sorted()
    }

    SideEffect {
        focoState.permissionGranted = true
        focoState.cameraReady = photon.isPreviewActive
        focoState.kind = if (photon.captureMode == CaptureMode.VIDEO) CaptureKind.VIDEO else CaptureKind.PHOTO
        focoState.lensFacing = currentCamera?.lensFacing ?: CameraMetadata.LENS_FACING_BACK
        focoState.zoom = viewModel.zoomRatioByMain.coerceIn(minZoom, maxZoom)
        focoState.minZoom = minZoom
        focoState.maxZoom = maxZoom
        focoState.zoomChips = zoomStops.ifEmpty { listOf(1f) }
        focoState.deviceRotation = photon.deviceRotation
        focoState.focusPoint = photon.focusPoint?.let { Offset(it.first, it.second) }
        focoState.focusLocked = photon.isFocusLocked
        focoState.recording = photon.videoRecordingState.isRecording
        focoState.recordingPaused = photon.videoRecordingState.isPaused
        focoState.recordingMs = photon.videoRecordingState.elapsedMs
        focoState.capturing = photon.isCapturing
        focoState.burstActive = photon.burstCapturing || photon.hdrBracketCapturing
        focoState.processing = photon.isMultiFrameEnabled
        focoState.countdown = photon.countdownValue.takeIf { it > 0 }
        focoState.micStatus = MicStatus.UNKNOWN
        focoState.readout = Readout(
            lens = when (photon.currentLensType.name) {
                "BACK_ULTRA_WIDE" -> LensKind.ULTRAWIDE
                "BACK_TELEPHOTO" -> LensKind.TELEPHOTO
                else -> LensKind.WIDE
            },
            aperture = photon.physicalAperture.takeIf { it > 0f },
            shutterNs = photon.shutterSpeed,
            iso = photon.iso,
            focusDiopters = photon.focusDistance.takeIf { it > 0f }
        )
        focoState.pro = ProState(
            evIndex = photon.exposureCompensation,
            wbKelvin = photon.awbTemperature.takeIf { photon.awbMode != CameraMetadata.CONTROL_AWB_MODE_AUTO },
            focusDiopters = photon.focusDistance.takeIf { !photon.isAutoFocus },
            shutterNs = photon.shutterSpeed.takeIf { !photon.isShutterSpeedAuto },
            iso = photon.iso.takeIf { !photon.isIsoAuto }
        )
        focoState.caps = com.hinnka.mycamera.focoui.ui.Capabilities(
            hasFlash = currentCamera?.lensFacing != CameraCharacteristics.LENS_FACING_FRONT,
            hasFront = photon.availableCameras.any { it.lensFacing == CameraCharacteristics.LENS_FACING_FRONT },
            rawSupported = photon.isRawSupported,
            ultraHdrSupported = Build.VERSION.SDK_INT >= 34,
            hiResWidth = photon.currentCaptureSize.width,
            hiResHeight = photon.currentCaptureSize.height,
            lensRatios = photon.availableCameras.associate { it.cameraId to it.displayIntrinsicZoomRatio },
            ultrawideId = photon.availableCameras.firstOrNull {
                it.lensType.name == "BACK_ULTRA_WIDE" || it.minZoom < 0.9f
            }?.cameraId,
            macroAvailable = photon.availableCameras.any { it.lensType.name == "BACK_MACRO" },
            manualLensAvailable = (currentCamera?.minimumFocusDistance ?: 0f) > 0f,
            uhdSupported = VideoResolutionPreset.UHD_2160P in photon.videoCapabilities.availableResolutions,
            fps60Supported = VideoFpsPreset.FPS_60 in photon.videoCapabilities.availableFps,
            stabilizationSupported = photon.videoCapabilities.availableStabilizationModes.isNotEmpty(),
            hdrVideoSupported = photon.videoCapabilities.linearTonemapSupported,
            evMin = photon.getExposureCompensationRange().lower,
            evMax = photon.getExposureCompensationRange().upper,
            evStep = photon.getExposureCompensationStep(),
            manualSensor = currentCamera?.isoRange != null && currentCamera.exposureTimeRange != null,
            isoMin = photon.getIsoRange().lower,
            isoMax = photon.getIsoRange().upper,
            shutterMinNs = photon.getManualShutterSpeedRange().lower,
            shutterMaxNs = photon.getManualShutterSpeedRange().upper,
            manualFocus = (currentCamera?.minimumFocusDistance ?: 0f) > 0f,
            minFocusDiopters = currentCamera?.minimumFocusDistance ?: 0f,
            cctSupported = photon.supportsCctWhiteBalance,
            awbModes = setOf(
                CameraMetadata.CONTROL_AWB_MODE_AUTO,
                CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
                CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
                CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
                CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            ),
            tonemapCurve = photon.videoCapabilities.linearTonemapSupported,
            equivalentFocalMm = currentCamera?.focalLength35mmEquivalent ?: 0f
        )
        focoSettings.timer = TimerSetting.entries.firstOrNull { it.seconds == photon.timerSeconds } ?: TimerSetting.OFF
        focoSettings.ratio = if (photon.aspectRatio == AspectRatio.RATIO_16_9) RatioSetting.WIDE_9_16 else RatioSetting.FULL_4_3
        focoSettings.raw = photon.useRaw
        focoSettings.resolution = if (photon.currentCaptureSize.width.toLong() * photon.currentCaptureSize.height >= 40_000_000L) ResolutionSetting.MP50 else ResolutionSetting.MP12
        focoSettings.videoResolution = if (photon.videoConfig.resolution == VideoResolutionPreset.UHD_2160P) VideoResolution.UHD else VideoResolution.FHD
        focoSettings.frameRate = if (photon.videoConfig.fps == VideoFpsPreset.FPS_60) FrameRate.FPS60 else FrameRate.FPS30
        focoSettings.stabilization = photon.videoConfig.stabilizationMode != VideoStabilizationMode.OFF
        focoSettings.hdrVideo = videoHdrEnabled
        focoSettings.torch = photon.videoConfig.torchEnabled
        focoSettings.grid = if (!photon.showGrid) GridSetting.NONE else when (photon.gridStyle) {
            GridStyle.GOLDEN_RATIO -> GridSetting.GOLDEN
            else -> GridSetting.THIRDS
        }
        focoSettings.level = levelIndicator
    }

    LaunchedEffect(initialized) {
        cameraPrepared = initialized && viewModel.prepareCamera()
    }
    LaunchedEffect(initialized, cameraPrepared, previewSurfaceTexture) {
        val surface = previewSurfaceTexture ?: return@LaunchedEffect
        if (initialized && cameraPrepared) viewModel.openCamera(surface)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.checkAndRecoverCamera()
        viewModel.refreshLocationOnResume()
        galleryViewModel.refreshLatestPhoto()
        viewModel.updateLut()
    }
    LaunchedEffect(Unit) {
        viewModel.imageSavedEvent.collect { galleryViewModel.refreshLatestPhoto() }
    }

    val actions = remember(viewModel, galleryViewModel, onGalleryClick, onSettingsClick, onFilterManagementClick, onFrameManagementClick, onToolboxClick, onPresetManagementClick) {
        object : FocoActions {
            override fun shutter() = viewModel.capture()
            override fun setKind(kind: CaptureKind) {
                viewModel.setCaptureMode(if (kind == CaptureKind.VIDEO) CaptureMode.VIDEO else CaptureMode.PHOTO)
            }
            override fun setPhotoMode(mode: PhotoMode) {
                focoState.photoMode = mode
                when (mode) {
                    PhotoMode.PHOTO -> Unit
                    PhotoMode.NIGHT -> {
                        viewModel.setUseJpgMax(true)
                        focoState.message = "Night mode uses Photon multi-frame capture; dedicated Foco Night processing is not ported."
                    }
                    PhotoMode.LONG_EXPOSURE, PhotoMode.ACTION_PAN, PhotoMode.PANORAMA -> {
                        focoState.photoMode = PhotoMode.PHOTO
                        focoState.message = "${mode.label} needs Foco's separate multi-frame processing engine and is not yet connected to Photon capture."
                    }
                }
            }
            override fun flip() = viewModel.switchCamera()
            override fun setZoom(zoom: Float, fromChip: Boolean) = viewModel.setZoomRatio(zoom.coerceIn(minZoom, maxZoom))
            override fun openGallery() = onGalleryClick()
            override fun focusAt(point: Offset, viewWidth: Int, viewHeight: Int, lock: Boolean) {
                if (viewWidth <= 0 || viewHeight <= 0) return
                if (lock) viewModel.lockFocusOnPoint(point.x, point.y, viewWidth, viewHeight)
                else viewModel.focusOnPoint(point.x, point.y, viewWidth, viewHeight)
            }
            override fun setPro(pro: ProState) {
                viewModel.setExposureCompensation(pro.evIndex)
                if (pro.iso == null) viewModel.setIsoAuto(true) else viewModel.setIso(pro.iso)
                if (pro.shutterNs == null) viewModel.setShutterSpeedAuto(true) else viewModel.setShutterSpeed(pro.shutterNs)
                if (pro.wbKelvin == null) viewModel.setAwbMode(CameraMetadata.CONTROL_AWB_MODE_AUTO)
                else viewModel.setAwbTemperature(pro.wbKelvin)
                if (pro.focusDiopters == null) viewModel.setAutoFocus(true) else viewModel.setFocusDistance(pro.focusDiopters)
            }
            override fun settingChanged(key: SettingKey) {
                when (key) {
                    SettingKey.FLASH -> {
                        val target = if (focoSettings.flash == FlashSetting.ON) 1 else 0
                        repeat(3) { if (viewModel.state.value.flashMode != target) viewModel.toggleFlash() }
                    }
                    SettingKey.TIMER -> repeat(4) {
                        if (viewModel.state.value.timerSeconds != focoSettings.timer.seconds) viewModel.toggleTimer()
                    }
                    SettingKey.MACRO -> {
                        val target = if (focoSettings.macro) {
                            viewModel.state.value.availableCameras.firstOrNull { it.lensType.name == "BACK_MACRO" }
                        } else {
                            viewModel.state.value.availableCameras.firstOrNull { it.lensType.name == "BACK_MAIN" }
                        }
                        target?.let { viewModel.switchToLens(it.cameraId) }
                    }
                    SettingKey.RATIO -> viewModel.setAspectRatio(
                        if (focoSettings.ratio == RatioSetting.WIDE_9_16) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
                    )
                    SettingKey.RESOLUTION -> viewModel.setUseJpgMax(focoSettings.resolution == ResolutionSetting.MP50)
                    SettingKey.RAW -> viewModel.setUseRaw(focoSettings.raw)
                    SettingKey.VIDEO_RESOLUTION -> {
                        val target = if (focoSettings.videoResolution == VideoResolution.UHD) VideoResolutionPreset.UHD_2160P else VideoResolutionPreset.FHD_1080P
                        if (target in viewModel.state.value.videoCapabilities.availableResolutions) viewModel.setVideoResolution(target)
                    }
                    SettingKey.FRAME_RATE -> {
                        val target = if (focoSettings.frameRate == FrameRate.FPS60) VideoFpsPreset.FPS_60 else VideoFpsPreset.FPS_30
                        if (target in viewModel.state.value.videoCapabilities.availableFps) viewModel.setVideoFps(target)
                    }
                    SettingKey.STABILIZATION -> viewModel.setVideoStabilizationMode(
                        if (focoSettings.stabilization) VideoStabilizationMode.EIS else VideoStabilizationMode.OFF
                    )
                    SettingKey.HDR_VIDEO -> viewModel.setVideoHdrEnabled(focoSettings.hdrVideo)
                    SettingKey.TORCH -> if (viewModel.state.value.captureMode == CaptureMode.VIDEO) {
                        viewModel.setVideoTorchEnabled(focoSettings.torch)
                    } else {
                        val target = if (focoSettings.torch) 2 else 0
                        repeat(3) { if (viewModel.state.value.flashMode != target) viewModel.toggleFlash() }
                    }
                    SettingKey.GRID -> {
                        viewModel.setShowGrid(focoSettings.grid != GridSetting.NONE)
                        viewModel.setGridStyle(if (focoSettings.grid == GridSetting.GOLDEN) GridStyle.GOLDEN_RATIO else GridStyle.THIRDS)
                    }
                    SettingKey.LEVEL -> viewModel.setShowLevelIndicator(focoSettings.level)
                    SettingKey.LOCATION -> viewModel.setSaveLocation(focoSettings.saveLocation)
                    SettingKey.SHUTTER_SOUND -> viewModel.setShutterSoundEnabled(focoSettings.shutterSound)
                    SettingKey.HAPTICS -> viewModel.setVibrationEnabled(focoSettings.haptics)
                    SettingKey.MIRROR_FRONT -> viewModel.setMirrorFrontCamera(focoSettings.mirrorFront)
                    SettingKey.VOLUME_KEYS -> viewModel.setVolumeKeyAction(when (focoSettings.volumeKeys) {
                        com.hinnka.mycamera.focoui.settings.VolumeKeyAction.SHUTTER -> PhotonVolumeKeyAction.CAPTURE
                        com.hinnka.mycamera.focoui.settings.VolumeKeyAction.ZOOM -> PhotonVolumeKeyAction.ZOOM
                        com.hinnka.mycamera.focoui.settings.VolumeKeyAction.VOLUME -> PhotonVolumeKeyAction.NONE
                    })
                    SettingKey.ULTRA_HDR -> viewModel.setUltraHdrGainMapEnabled(focoSettings.ultraHdr)
                    SettingKey.PROCESSING -> viewModel.setUseJpgMax(focoSettings.processing)
                    SettingKey.QR -> {
                        focoSettings.qrDetection = false
                        focoState.message = "QR scanning is not connected to PhotonCamera yet."
                    }
                    SettingKey.LENS_SELECTION -> {
                        focoState.message = "PhotonCamera selects physical lenses through its native zoom and lens controls."
                    }
                    else -> Unit
                }
            }
            override fun pauseResume() {
                if (viewModel.state.value.videoRecordingState.isPaused) viewModel.resumeVideoRecording()
                else viewModel.pauseVideoRecording()
            }
            override fun snapshot() = viewModel.captureVideoFrame()
            override fun qrClicked(text: String) {
                if (text.startsWith("https://") || text.startsWith("http://")) openUrl(text)
                else focoState.message = text
            }
            override fun haptic() {
                localView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            override fun openUrl(url: String) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            override fun openPhotonSettings() = onSettingsClick()
            override fun openPhotonFilters() = onFilterManagementClick(null)
            override fun openPhotonFrames() = onFrameManagementClick()
            override fun openPhotonToolbox() = onToolboxClick()
            override fun openPhotonPresets() = onPresetManagementClick()
            override fun requestPermission() {
                Toast.makeText(context, "Camera and microphone permissions are managed by Photon Camera.", Toast.LENGTH_SHORT).show()
            }
            override fun openAppInfo() {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
            }
        }
    }

    val calibrationOffset by viewModel.getCameraOrientationOffset(photon.currentCameraId).collectAsState(initial = 0)
    val colorRecipe = viewModel.getMergedRecipeParams(
        if (photon.captureMode == CaptureMode.VIDEO) videoRecipe ?: photoRecipe else photoRecipe,
        forVideo = photon.captureMode == CaptureMode.VIDEO
    )

    FocoTheme {
        if (focoState.fullSettingsOpen) {
            FocoSettingsScreen(focoState, focoSettings, actions)
        } else {
            com.hinnka.mycamera.focoui.ui.CameraScreen(
                state = focoState,
                settings = focoSettings,
                actions = actions,
                preview = { previewModifier ->
                    if (initialized && cameraPrepared) {
                        CameraPreviewGL(
                            aspectRatio = photon.getPreviewAspectRatio(),
                            previewSize = photon.currentPreviewSize,
                            captureSize = photon.currentCaptureSize,
                            captureMode = photon.captureMode,
                            sensorOrientation = currentCamera?.sensorOrientation ?: 0,
                            lensFacing = currentCamera?.lensFacing ?: CameraCharacteristics.LENS_FACING_BACK,
                            calibrationOffset = calibrationOffset,
                            baselineLut = viewModel.currentBaselineLutConfig,
                            currentLut = viewModel.currentLutConfig,
                            enginePreviewPlan = viewModel.currentEnginePreviewPlan,
                            baselineColorRecipeParams = baselineRecipe,
                            colorRecipeParams = colorRecipe,
                            focusPoint = photon.focusPoint,
                            meteringPoint = photon.meteringPoint,
                            canMoveMeteringPoint = photon.supportsPointMetering && !photon.isCapturing,
                            onMeteringPointMove = viewModel::moveMeteringPoint,
                            onFocusPointMove = viewModel::moveFocusPoint,
                            onMeteringPointMerge = viewModel::mergeMeteringPoint,
                            focusPointSource = photon.focusPointSource,
                            isFocusLocked = photon.isFocusLocked,
                            isFocusing = photon.isFocusing,
                            focusSuccess = photon.focusSuccess,
                            meteringMode = photon.effectiveMeteringMode,
                            onSurfaceTextureReady = { previewSurfaceTexture = it },
                            onSurfaceDestroyed = { surface ->
                                if (previewSurfaceTexture === surface) previewSurfaceTexture = null
                                viewModel.closeCamera(surface)
                            },
                            onTap = { _, _, _, _ -> },
                            onLongPress = { _, _, _, _ -> },
                            onHistogramUpdated = viewModel::handleHistogramUpdate,
                            onMeteringUpdated = viewModel::handleMeteringUpdate,
                            onHighlightPointUpdated = viewModel::handleHighlightPointUpdate,
                            onFirstPreviewFrame = viewModel::onFirstPreviewFrame,
                            onGLSurfaceViewReady = { viewModel.glSurfaceView = it },
                            livePhotoRecorder = viewModel.livePhotoRecorder,
                            videoLogProfile = photon.videoConfig.logProfile,
                            isAutoFocus = photon.isAutoFocus,
                            stabilizationCoordinator = viewModel.realtimeStabilizationCoordinator,
                            stabilizationInputSize = if (
                                photon.captureMode == CaptureMode.VIDEO &&
                                photon.videoConfig.stabilizationMode == VideoStabilizationMode.ENHANCED
                            ) {
                                photon.videoCapabilities.enhancedStabilizationInputSizesByResolution[photon.videoConfig.resolution]
                                    ?: photon.currentPreviewSize
                            } else photon.currentPreviewSize,
                            photoPreviewStabilizationEnabled = photon.photoPreviewStabilizationEnabled,
                            videoPreviewStabilizationEnabled = photon.videoConfig.stabilizationMode == VideoStabilizationMode.ENHANCED,
                            videoPreviewStabilizationStrength = photon.videoConfig.enhancedStabilizationStrength,
                            videoPreviewStabilizationLookahead = photon.videoConfig.enhancedStabilizationLookahead,
                            modifier = previewModifier
                        )
                    }
                }
            )
        }
    }
}
